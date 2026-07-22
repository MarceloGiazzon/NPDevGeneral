# NPDev CLI

`npdev` is the portable root command for core NPDev operations. It uses `NPDEV_ROOT` when set and otherwise resolves the repository root from the checked-out command location.

```bash
./npdev --version
./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json
./npdev normalize ai-model golden-ai-scenarios/base-ai-loop/ai-model.json
./npdev generate app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json --config NPDevContract/dsl/resources/Models/canonical-demo/config.json --output build/npdev-generated
./npdev report bootstrap
```
