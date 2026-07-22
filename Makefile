NPDEV ?= ./npdev
MODEL ?= NPDevContract/dsl/resources/Models/canonical-demo/model.json
CONFIG ?= NPDevContract/dsl/resources/Models/canonical-demo/config.json
OUTPUT ?= build/npdev-generated

.PHONY: help version validate-model normalize-ai generate-app report-bootstrap maturity-check final-evidence

help:
	@echo "NPDev portable targets"
	@echo "  make version          - print portable CLI version"
	@echo "  make validate-model   - validate the canonical demo model"
	@echo "  make normalize-ai     - normalize the base AI loop model"
	@echo "  make generate-app     - generate the canonical demo app"
	@echo "  make report-bootstrap - bootstrap maturity reports"
	@echo "  make maturity-check   - run Gradle post-Beta0 maturity check"
	@echo "  make final-evidence   - validate reports and generate final evidence manifest"

version:
	$(NPDEV) --version

validate-model:
	$(NPDEV) validate model $(MODEL)

normalize-ai:
	$(NPDEV) normalize ai-model golden-ai-scenarios/base-ai-loop/ai-model.json

generate-app:
	$(NPDEV) generate app --model $(MODEL) --config $(CONFIG) --output $(OUTPUT)

report-bootstrap:
	$(NPDEV) report bootstrap

maturity-check:
	./gradlew postBeta0MaturityCheck --no-daemon --console=plain

final-evidence:
	pwsh -NoProfile -File scripts/quality/validate-report-schemas.ps1
	pwsh -NoProfile -File scripts/quality/generate-final-evidence-bundle.ps1
	pwsh -NoProfile -File scripts/quality/run-report-schema-validation.ps1
