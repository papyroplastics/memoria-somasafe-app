SHELL := bash

shared_repo := https://github.com/papyroplastics/memoria-somasafe-shared.git

.PHONY: shared assemble install uninstall clean

shared:
	@if [ -e shared ] || [ -L shared ]; then \
		echo "shared already present"; \
	elif [ -d ../shared ]; then \
		ln -sr ../shared/ .; \
	else \
		git clone ${shared_repo} shared; \
	fi

assemble: shared
	./gradlew assembleDebug

install: shared
	./gradlew installDebug

uninstall:
	./gradlew uninstallDebug

clean:
	./gradlew clean
