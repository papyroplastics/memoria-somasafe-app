#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include <cstdint>

#include <litert/cc/litert_buffer_ref.h>
#include <litert/cc/litert_compiled_model.h>
#include <litert/cc/litert_environment.h>

#include "signatures.h"
#include "utils.h"
#include "introspect.h"

#define LOG_TAG "SomaSafeML"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Owns the model bytes, the environment and the compiled model. The model is
// built from a buffer view over `data`, so `data` must outlive the model; the
// environment must too. Declaration order is destruction order reversed, so
// listing them data → env → model destroys model first and data last.
struct ModelHandle {
    std::vector<uint8_t> data;
    litert::Environment env;
    litert::CompiledModel model;
};

static void throw_error(JNIEnv* jni, const std::string& msg) {
    LOGE("%s", msg.c_str());
    jclass cls = jni->FindClass("java/lang/RuntimeException");
    jni->ThrowNew(cls, msg.c_str());
}

// Unpacks a Kotlin Array<FloatArray> into parallel C++ vectors + span views.
static bool unpack_float_arrays(
    JNIEnv* jni,
    jobjectArray arrays,
    std::vector<std::vector<float>>& storage,
    std::vector<absl::Span<const float>>& spans
) {
    jsize n = jni->GetArrayLength(arrays);
    storage.resize(n);
    spans.resize(n);
    for (jsize i = 0; i < n; ++i) {
        auto arr = (jfloatArray) jni->GetObjectArrayElement(arrays, i);
        jsize len = jni->GetArrayLength(arr);
        storage[i].resize(len);
        jni->GetFloatArrayRegion(arr, 0, len, storage[i].data());
        spans[i] = absl::MakeConstSpan(storage[i]);
        jni->DeleteLocalRef(arr);
    }
    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_somasafe_training_domain_LiteRtModel_nativeCreate(JNIEnv* jni, jobject, jbyteArray model_bytes) {
    jsize len = jni->GetArrayLength(model_bytes);
    std::vector<uint8_t> data(len);
    jni->GetByteArrayRegion(model_bytes, 0, len, reinterpret_cast<jbyte*>(data.data()));

    auto env_result = litert::Environment::Create({});
    if (!env_result) {
        throw_error(jni, "Failed to create LiteRT environment: " + env_result.Error().Message());
        return 0;
    }

    // The buffer view aliases `data`; moving the vector into the handle below
    // preserves its heap pointer, so the view stays valid for the model's life.
    auto model_result = litert::CompiledModel::Create(
        *env_result, litert::BufferRef<uint8_t>(data.data(), data.size()),
        litert::HwAccelerators::kCpu);
    if (!model_result) {
        throw_error(jni, "Failed to load model: " + model_result.Error().Message());
        return 0;
    }

    auto* handle = new ModelHandle{
        std::move(data), std::move(*env_result), std::move(*model_result)};
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_app_somasafe_training_domain_LiteRtModel_nativeDestroy(JNIEnv*, jobject, jlong ptr) {
    delete reinterpret_cast<ModelHandle*>(ptr);
}

// inputs: Array<FloatArray> ordered by eval signature tensor index
// returns: Array<FloatArray> ordered by eval signature output tensor index
JNIEXPORT jobjectArray JNICALL
Java_app_somasafe_training_domain_LiteRtModel_nativeRunEval(JNIEnv* jni, jobject, jlong ptr, jobjectArray inputs) {
    auto* h = reinterpret_cast<ModelHandle*>(ptr);

    std::vector<std::vector<float>> storage;
    std::vector<absl::Span<const float>> spans;
    if (!unpack_float_arrays(jni, inputs, storage, spans)) return nullptr;

    auto result = run_model(h->model, EVAL_SIG_NAME, spans);
    if (!result) {
        throw_error(jni, result.Error().Message());
        return nullptr;
    }

    const auto& outputs = *result;
    jclass float_arr_cls = jni->FindClass("[F");
    jobjectArray out = jni->NewObjectArray((jsize) outputs.size(), float_arr_cls, nullptr);
    jni->DeleteLocalRef(float_arr_cls);

    for (jsize i = 0; i < (jsize) outputs.size(); ++i) {
        jfloatArray arr = jni->NewFloatArray((jsize) outputs[i].size());
        jni->SetFloatArrayRegion(arr, 0, (jsize) outputs[i].size(), outputs[i].data());
        jni->SetObjectArrayElement(out, i, arr);
        jni->DeleteLocalRef(arr);
    }
    return out;
}

// Quantizes float input → runs the int8 eval signature → dequantizes to float.
// Quantization parameters are read from tensor indices 0 (input) and 0 (output).
JNIEXPORT jfloatArray JNICALL
Java_app_somasafe_training_domain_LiteRtModel_nativeRunEvalQuantized(JNIEnv* jni, jobject, jlong ptr, jfloatArray input) {
    auto* h = reinterpret_cast<ModelHandle*>(ptr);
    jsize len = jni->GetArrayLength(input);

    auto quant_result = get_per_tensor_quantization(h->model, EVAL_SIG_NAME, 0, 0);
    if (!quant_result) {
        throw_error(jni, quant_result.Error().Message());
        return nullptr;
    }
    const auto in_q  = quant_result->input;
    const auto out_q = quant_result->output;

    std::vector<float>  float_in(len);
    std::vector<int8_t> quant_in(len);
    jni->GetFloatArrayRegion(input, 0, len, float_in.data());

    auto r1 = quantize(in_q, absl::MakeConstSpan(float_in), absl::MakeSpan(quant_in));
    if (!r1) { throw_error(jni, r1.Error().Message()); return nullptr; }

    auto run_result = run_model_quantized(h->model, EVAL_SIG_NAME, absl::MakeConstSpan(quant_in));
    if (!run_result) { throw_error(jni, run_result.Error().Message()); return nullptr; }
    const auto& quant_out = *run_result;

    std::vector<float> float_out(quant_out.size());
    auto r3 = dequantize(out_q, absl::MakeConstSpan(quant_out), absl::MakeSpan(float_out));
    if (!r3) { throw_error(jni, r3.Error().Message()); return nullptr; }

    jfloatArray out = jni->NewFloatArray((jsize) float_out.size());
    jni->SetFloatArrayRegion(out, 0, (jsize) float_out.size(), float_out.data());
    return out;
}

// inputs: Array<FloatArray> ordered by train signature tensor index
// returns: final epoch average loss
JNIEXPORT jfloat JNICALL
Java_app_somasafe_training_domain_LiteRtModel_nativeTrain(JNIEnv* jni, jobject, jlong ptr,
                                          jobjectArray inputs, jint epochs) {
    auto* h = reinterpret_cast<ModelHandle*>(ptr);

    std::vector<std::vector<float>> storage;
    std::vector<absl::Span<const float>> spans;
    if (!unpack_float_arrays(jni, inputs, storage, spans)) return 0.0f;

    auto result = train_model(h->model, TRAIN_SIG_NAME,
        static_cast<uint32_t>(epochs), spans);
    if (!result) {
        throw_error(jni, result.Error().Message());
        return 0.0f;
    }
    return static_cast<jfloat>(*result);
}

JNIEXPORT jfloatArray JNICALL
Java_app_somasafe_training_domain_LiteRtModel_nativeSaveWeights(JNIEnv* jni, jobject, jlong ptr) {
    auto* h = reinterpret_cast<ModelHandle*>(ptr);

    auto result = save_weights(h->model);
    if (!result) {
        throw_error(jni, result.Error().Message());
        return nullptr;
    }
    const auto& params = *result;
    jfloatArray out = jni->NewFloatArray(static_cast<jsize>(params.size()));
    jni->SetFloatArrayRegion(out, 0, static_cast<jsize>(params.size()), params.data());
    return out;
}

JNIEXPORT void JNICALL
Java_app_somasafe_training_domain_LiteRtModel_nativeRestoreWeights(JNIEnv* jni, jobject, jlong ptr, jfloatArray weights) {
    auto* h = reinterpret_cast<ModelHandle*>(ptr);

    jsize len = jni->GetArrayLength(weights);
    std::vector<float> params(len);
    jni->GetFloatArrayRegion(weights, 0, len, params.data());

    auto result = restore_weights(h->model, absl::MakeConstSpan(params));
    if (!result) {
        throw_error(jni, result.Error().Message());
    }
}

// ── JNI object builders ───────────────────────────────────────────────────────

static jobject build_quant_info(JNIEnv* jni,
                                jclass cls, jmethodID ctor,
                                const QuantizationInfoData& q) {
    const char* type_cstr;
    switch (q.type) {
        case QuantizationInfoData::Type::PerTensor:  type_cstr = "per_tensor";  break;
        case QuantizationInfoData::Type::PerChannel: type_cstr = "per_channel"; break;
        case QuantizationInfoData::Type::BlockWise:  type_cstr = "block_wise";  break;
        case QuantizationInfoData::Type::Unknown:    type_cstr = "unknown";     break;
        default:                                     type_cstr = "none";        break;
    }

    jstring  type_str  = jni->NewStringUTF(type_cstr);
    jfloatArray scales = jni->NewFloatArray((jsize)q.scales.size());
    jintArray   zp_arr = jni->NewIntArray((jsize)q.zero_points.size());

    if (!q.scales.empty())
        jni->SetFloatArrayRegion(scales, 0, (jsize)q.scales.size(),
            reinterpret_cast<const jfloat*>(q.scales.data()));
    if (!q.zero_points.empty())
        jni->SetIntArrayRegion(zp_arr, 0, (jsize)q.zero_points.size(),
            reinterpret_cast<const jint*>(q.zero_points.data()));

    jobject obj = jni->NewObject(cls, ctor,
        type_str, (jfloat)q.scale, (jint)q.zero_point, (jint)q.quantized_dim,
        scales, zp_arr);

    jni->DeleteLocalRef(type_str);
    jni->DeleteLocalRef(scales);
    jni->DeleteLocalRef(zp_arr);
    return obj;
}

static jobject build_tensor_info(JNIEnv* jni,
                                 jclass tensor_cls, jmethodID tensor_ctor,
                                 jclass quant_cls,  jmethodID quant_ctor,
                                 const TensorInfoData& t) {
    jstring     name      = jni->NewStringUTF(t.name.c_str());
    jstring     etype     = jni->NewStringUTF(t.element_type.c_str());
    jintArray   shape_arr = jni->NewIntArray((jsize)t.shape.size());
    if (!t.shape.empty())
        jni->SetIntArrayRegion(shape_arr, 0, (jsize)t.shape.size(),
            reinterpret_cast<const jint*>(t.shape.data()));

    jobject quant = build_quant_info(jni, quant_cls, quant_ctor, t.quantization);
    jobject obj   = jni->NewObject(tensor_cls, tensor_ctor,
        name, etype, (jboolean)t.is_ranked, shape_arr, quant);

    jni->DeleteLocalRef(name);
    jni->DeleteLocalRef(etype);
    jni->DeleteLocalRef(shape_arr);
    jni->DeleteLocalRef(quant);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_app_somasafe_training_domain_LiteRtModel_nativeDescribe(JNIEnv* jni, jobject, jlong ptr, jstring name) {
    auto* h = reinterpret_cast<ModelHandle*>(ptr);

    const char* cname = jni->GetStringUTFChars(name, nullptr);
    auto result = get_model_info(h->model, cname);
    jni->ReleaseStringUTFChars(name, cname);

    if (!result) {
        throw_error(jni, result.Error().Message());
        return nullptr;
    }
    const ModelInfoData& data = *result;

    // Look up all classes and method IDs up front.
    jclass quant_cls    = jni->FindClass("app/somasafe/training/domain/QuantizationInfo");
    jclass tensor_cls   = jni->FindClass("app/somasafe/training/domain/TensorInfo");
    jclass sig_cls      = jni->FindClass("app/somasafe/training/domain/SignatureInfo");
    jclass model_cls    = jni->FindClass("app/somasafe/training/domain/ModelInfo");
    jclass list_cls     = jni->FindClass("java/util/ArrayList");
    if (!quant_cls || !tensor_cls || !sig_cls || !model_cls || !list_cls) return nullptr;

    jmethodID quant_ctor  = jni->GetMethodID(quant_cls,  "<init>", "(Ljava/lang/String;FII[F[I)V");
    jmethodID tensor_ctor = jni->GetMethodID(tensor_cls, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Z[ILapp/somasafe/training/domain/QuantizationInfo;)V");
    jmethodID sig_ctor    = jni->GetMethodID(sig_cls,    "<init>",
        "(Ljava/lang/String;Ljava/util/List;Ljava/util/List;)V");
    jmethodID model_ctor  = jni->GetMethodID(model_cls,  "<init>",
        "(Ljava/lang/String;Ljava/util/List;)V");
    jmethodID list_ctor   = jni->GetMethodID(list_cls,   "<init>", "()V");
    jmethodID list_add    = jni->GetMethodID(list_cls,   "add",    "(Ljava/lang/Object;)Z");

    // Build the signatures list.
    jobject sigs_list = jni->NewObject(list_cls, list_ctor);
    for (const auto& sig : data.signatures) {
        jobject inputs_list  = jni->NewObject(list_cls, list_ctor);
        jobject outputs_list = jni->NewObject(list_cls, list_ctor);

        for (const auto& t : sig.inputs) {
            jobject tensor = build_tensor_info(jni, tensor_cls, tensor_ctor,
                                               quant_cls, quant_ctor, t);
            jni->CallBooleanMethod(inputs_list, list_add, tensor);
            jni->DeleteLocalRef(tensor);
        }
        for (const auto& t : sig.outputs) {
            jobject tensor = build_tensor_info(jni, tensor_cls, tensor_ctor,
                                               quant_cls, quant_ctor, t);
            jni->CallBooleanMethod(outputs_list, list_add, tensor);
            jni->DeleteLocalRef(tensor);
        }

        jstring  sig_key = jni->NewStringUTF(sig.key.c_str());
        jobject  sig_obj = jni->NewObject(sig_cls, sig_ctor,
            sig_key, inputs_list, outputs_list);

        jni->CallBooleanMethod(sigs_list, list_add, sig_obj);
        jni->DeleteLocalRef(sig_key);
        jni->DeleteLocalRef(inputs_list);
        jni->DeleteLocalRef(outputs_list);
        jni->DeleteLocalRef(sig_obj);
    }

    jstring model_name = jni->NewStringUTF(data.name.c_str());
    jobject model_obj  = jni->NewObject(model_cls, model_ctor, model_name, sigs_list);

    jni->DeleteLocalRef(model_name);
    jni->DeleteLocalRef(sigs_list);
    jni->DeleteLocalRef(quant_cls);
    jni->DeleteLocalRef(tensor_cls);
    jni->DeleteLocalRef(sig_cls);
    jni->DeleteLocalRef(model_cls);
    jni->DeleteLocalRef(list_cls);
    return model_obj;
}

} // extern "C"
