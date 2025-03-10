MKFILE_PATH 			:= $(abspath $(lastword $(MAKEFILE_LIST)))
WORKING_DIR 			:= $(dir $(MKFILE_PATH))
DOCKERFILES_DIR			:= $(WORKING_DIR)/dockerfiles

ifeq ($(REGISTRY),)
    REGISTRY := registry.cn-hongkong.aliyuncs.com
endif

# x86_64 or aarch64
ARCH := $(shell uname -m)

VERSION ?= latest
VINEYARD_VERSION ?= v0.21.3
# This is the version of builder base image in most cases, except for graphscope-dev
BUILDER_VERSION ?= $(VINEYARD_VERSION)
# This is the version of runtime base image
RUNTIME_VERSION ?= $(VINEYARD_VERSION)
PROFILE ?= release
CI ?= false

BUILD_PROGRESS  	?= auto

.PHONY: all graphscope

# High order modules
.PHONY: coordinator analytical interactive learning graphscope-store

# Base images and develop images
.PHONY:  graphscope-dev-base graphscope-dev vineyard-dev vineyard-runtime

# Low order modules
.PHONY:  analytical analytical-java
.PHONY:  interactive-frontend interactive-executor interactive-experimental
.PHONY:  learning

# Target hierarchy
interactive: interactive-frontend interactive-executor

all: coordinator analytical interactive learning
graphscope: all

dev-wheel:
	cd $(WORKING_DIR) && \
	cd ../python/graphscope && \
	docker build \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg VINEYARD_VERSION=${VINEYARD_VERSION} \
		-t graphscope/graphscope-dev:wheel-${VINEYARD_VERSION}-${ARCH} \
		-f $(DOCKERFILES_DIR)/manylinux2014.Dockerfile .


manylinux2014-ext:
	cd $(WORKING_DIR) && \
	cd ../python/graphscope  && \
	docker build \
		--progress=$(BUILD_PROGRESS) \
		-t graphscope/manylinux2014:ext-${ARCH} \
		-f $(DOCKERFILES_DIR)/manylinux2014-ext.Dockerfile .

graphscope-dev:
	cd $(WORKING_DIR) && \
	cd ../python/graphscope  && \
	docker build \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg VINEYARD_VERSION=${VINEYARD_VERSION} \
		-t graphscope/graphscope-dev:${VINEYARD_VERSION}-${ARCH} \
		-f $(DOCKERFILES_DIR)/graphscope-dev.Dockerfile .

vineyard-dev:
	cd $(WORKING_DIR) && \
	cd ../python/graphscope  && \
	docker build \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg VINEYARD_VERSION=${VINEYARD_VERSION} \
		-t graphscope/vineyard-dev:${VINEYARD_VERSION}-${ARCH} \
		-f $(DOCKERFILES_DIR)/vineyard-dev.Dockerfile .

vineyard-runtime:
	cd $(WORKING_DIR) && \
	docker build \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg BUILDER_VERSION=$(BUILDER_VERSION) \
		-t graphscope/vineyard-runtime:${VINEYARD_VERSION}-${ARCH} \
		-f $(DOCKERFILES_DIR)/vineyard-runtime.Dockerfile .

coordinator:
	cd $(WORKING_DIR)/.. && \
	docker build \
	    --target coordinator \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg BUILDER_VERSION=$(BUILDER_VERSION) \
		--build-arg CI=${CI} \
		-t graphscope/coordinator:${VERSION} \
		-f $(DOCKERFILES_DIR)/coordinator.Dockerfile .

analytical:
	cd $(WORKING_DIR)/.. && \
	docker build \
	    --target analytical \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg BUILDER_VERSION=$(BUILDER_VERSION) \
		--build-arg RUNTIME_VERSION=$(RUNTIME_VERSION) \
		--build-arg CI=${CI} \
		-t graphscope/analytical:${VERSION} \
		-f $(DOCKERFILES_DIR)/analytical.Dockerfile .

analytical-java:
	cd $(WORKING_DIR)/.. && \
	docker build \
	    --target analytical-java \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg BUILDER_VERSION=$(BUILDER_VERSION) \
		--build-arg RUNTIME_VERSION=$(RUNTIME_VERSION) \
		--build-arg CI=${CI} \
		-t graphscope/analytical-java:${VERSION} \
		-f $(DOCKERFILES_DIR)/analytical.Dockerfile .

interactive-frontend:
	cd $(WORKING_DIR)/.. && \
	docker build \
	    --target frontend \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg BUILDER_VERSION=$(BUILDER_VERSION) \
		--build-arg RUNTIME_VERSION=$(RUNTIME_VERSION) \
		--build-arg profile=$(PROFILE) \
		--build-arg CI=${CI} \
		-t graphscope/interactive-frontend:${VERSION} \
		-f $(DOCKERFILES_DIR)/interactive.Dockerfile .

interactive-executor:
	cd $(WORKING_DIR)/.. \
	&& docker build \
	    --target executor \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg BUILDER_VERSION=$(BUILDER_VERSION) \
		--build-arg RUNTIME_VERSION=$(RUNTIME_VERSION) \
		--build-arg profile=$(PROFILE) \
		--build-arg CI=${CI} \
		-t graphscope/interactive-executor:${VERSION} \
		-f $(DOCKERFILES_DIR)/interactive.Dockerfile .

# gie with experimental storage
interactive-experimental:
	cd $(WORKING_DIR)/.. && \
	docker build \
	    --target experimental \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg BUILDER_VERSION=$(BUILDER_VERSION) \
		-t graphscope/interactive-experimental:${VERSION} \
		-f $(DOCKERFILES_DIR)/interactive-experimental.Dockerfile .

learning:
	cd $(WORKING_DIR)/.. && \
	docker build \
	    --target learning \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg BUILDER_VERSION=$(BUILDER_VERSION) \
		--build-arg RUNTIME_VERSION=$(RUNTIME_VERSION) \
		--build-arg CI=${CI} \
		-t graphscope/learning:${VERSION} \
		-f $(DOCKERFILES_DIR)/learning.Dockerfile .

graphscope-store:
	cd $(WORKING_DIR)/.. && \
	docker build \
		--progress=$(BUILD_PROGRESS) \
		--build-arg REGISTRY=$(REGISTRY) \
		--build-arg BUILDER_VERSION=$(BUILDER_VERSION) \
		--build-arg profile=${PROFILE} \
		-t graphscope/graphscope-store:${VERSION} \
		-f $(DOCKERFILES_DIR)/graphscope-store.Dockerfile .
