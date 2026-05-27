#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include <litert/cc/litert_compiled_model.h>
#include <litert/cc/litert_environment.h>

#include "signatures.h"
#include "utils.h"

#define LOG_TAG "SomaSafeML"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Owns both the environment and the compiled model. The environment must
// outlive the model — storing them together in a single heap allocation
// guarantees that ordering on destruction.
struct ModelHandle {
    litert::Environment env;
    litert::CompiledModel model;
};

static void throw_error(JNIEnv* jni, const std::string& msg) {
    LOGE("%s", msg.c_str());
    jclass cls = jni->FindClass("java/lang/RuntimeException");
    jni->ThrowNew(cls, msg.c_str());
}

static ModelHandle* to_handle(jlong ptr) {
    return reinterpret_cast<ModelHandle*>(ptr);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_somasafe_LiteRtModel_nativeCreate(JNIEnv* jni, jobject, jstring path) {
    const char* cpath = jni->GetStringUTFChars(path, nullptr);
    std::string model_path(cpath);
    jni->ReleaseStringUTFChars(path, cpath);

    auto env_result = litert::Environment::Create({});
    if (!env_result) {
        throw_error(jni, "Failed to create LiteRT environment: " + env_result.Error().Message());
        return 0;
    }

    auto model_result = litert::CompiledModel::Create(
        *env_result, model_path, litert::HwAccelerators::kCpu);
    if (!model_result) {
        throw_error(jni, "Failed to load model: " + model_result.Error().Message());
        return 0;
    }

    auto* handle = new ModelHandle{std::move(*env_result), std::move(*model_result)};
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_app_somasafe_LiteRtModel_nativeDestroy(JNIEnv*, jobject, jlong ptr) {
    delete to_handle(ptr);
}

JNIEXPORT jfloatArray JNICALL
Java_app_somasafe_LiteRtModel_nativeRunEval(JNIEnv* jni, jobject, jlong ptr, jfloatArray input) {
    auto* h = to_handle(ptr);
    jsize len = jni->GetArrayLength(input);

    std::vector<float> in_data(len);
    jni->GetFloatArrayRegion(input, 0, len, in_data.data());
    std::vector<float> out_data(len);

    auto result = run_model<float>(h->model,
        absl::MakeConstSpan(in_data), absl::MakeSpan(out_data));
    if (!result) {
        throw_error(jni, result.Error().Message());
        return nullptr;
    }

    jfloatArray out = jni->NewFloatArray(len);
    jni->SetFloatArrayRegion(out, 0, len, out_data.data());
    return out;
}

// Quantizes float input → runs the int8 eval signature → dequantizes to float.
// Quantization parameters are read from the model's eval signature tensors.
JNIEXPORT jfloatArray JNICALL
Java_app_somasafe_LiteRtModel_nativeRunEvalQuantized(JNIEnv* jni, jobject, jlong ptr, jfloatArray input) {
    auto* h = to_handle(ptr);
    jsize len = jni->GetArrayLength(input);

    auto sig_result = h->model.FindSignature(EVAL_SIG_NAME);
    if (!sig_result) {
        throw_error(jni, "eval signature not found: " + sig_result.Error().Message());
        return nullptr;
    }
    auto in_tensor  = sig_result->InputTensor(DATA_IN_BUF_NAME);
    auto out_tensor = sig_result->OutputTensor(RES_OUT_BUF_NAME);
    if (!in_tensor || !out_tensor) {
        throw_error(jni, "eval tensor not found");
        return nullptr;
    }
    const auto in_q  = in_tensor->PerTensorQuantization();
    const auto out_q = out_tensor->PerTensorQuantization();

    std::vector<float>  float_in(len);
    std::vector<int8_t> quant_in(len);
    std::vector<int8_t> quant_out(len);
    std::vector<float>  float_out(len);
    jni->GetFloatArrayRegion(input, 0, len, float_in.data());

    auto r1 = quantize(in_q, absl::MakeConstSpan(float_in), absl::MakeSpan(quant_in));
    if (!r1) { throw_error(jni, r1.Error().Message()); return nullptr; }

    auto r2 = run_model<int8_t>(h->model, absl::MakeConstSpan(quant_in), absl::MakeSpan(quant_out));
    if (!r2) { throw_error(jni, r2.Error().Message()); return nullptr; }

    auto r3 = dequantize(out_q, absl::MakeConstSpan(quant_out), absl::MakeSpan(float_out));
    if (!r3) { throw_error(jni, r3.Error().Message()); return nullptr; }

    jfloatArray out = jni->NewFloatArray(len);
    jni->SetFloatArrayRegion(out, 0, len, float_out.data());
    return out;
}

JNIEXPORT jfloat JNICALL
Java_app_somasafe_LiteRtModel_nativeTrain(JNIEnv* jni, jobject, jlong ptr,
                                          jfloatArray data, jfloatArray labels, jint epochs) {
    auto* h = to_handle(ptr);

    jsize data_len  = jni->GetArrayLength(data);
    jsize label_len = jni->GetArrayLength(labels);
    std::vector<float> data_vec(data_len);
    std::vector<float> label_vec(label_len);
    jni->GetFloatArrayRegion(data,   0, data_len,  data_vec.data());
    jni->GetFloatArrayRegion(labels, 0, label_len, label_vec.data());

    auto result = train_model(h->model, static_cast<uint32_t>(epochs),
        absl::MakeConstSpan(data_vec), absl::MakeConstSpan(label_vec));
    if (!result) {
        throw_error(jni, result.Error().Message());
        return 0.0f;
    }
    return static_cast<jfloat>(*result);
}

JNIEXPORT jfloatArray JNICALL
Java_app_somasafe_LiteRtModel_nativeSaveWeights(JNIEnv* jni, jobject, jlong ptr) {
    auto* h = to_handle(ptr);

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
Java_app_somasafe_LiteRtModel_nativeRestoreWeights(JNIEnv* jni, jobject, jlong ptr, jfloatArray weights) {
    auto* h = to_handle(ptr);

    jsize len = jni->GetArrayLength(weights);
    std::vector<float> params(len);
    jni->GetFloatArrayRegion(weights, 0, len, params.data());

    auto result = restore_weights(h->model, absl::MakeConstSpan(params));
    if (!result) {
        throw_error(jni, result.Error().Message());
    }
}

} // extern "C"
