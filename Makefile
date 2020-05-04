REGISTRY_PREFIX ?= docker.io/radixdlt/
TAG ?= latest

.PHONY: faucet
faucet:
	./gradlew clean distTar
	docker build -t $(REGISTRY_PREFIX)faucet:$(TAG) -f ./Dockerfile.alpine .

.PHONY: faucet-push
faucet-push: faucet
	docker push $(REGISTRY_PREFIX)faucet:$(TAG)
