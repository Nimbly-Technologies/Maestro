# Local Jev healing in Maestro

This fork adds an opt-in Android `tapOn` recovery path. Maestro performs the normal element lookup first, with an optional per-flow bound for eligible taps. When that lookup raises `ElementNotFound`, it reads the current accessibility hierarchy, builds a bounded list of enabled and visible controls, and asks the local `jev-mobile-adapter` to choose one. The original selector is rechecked after the provider responds, and a replacement is tapped only after it still matches a fresh hierarchy.

Enable it in a flow config:

```yaml
appId: com.sustainnovationgroup.nimblylite
jevHealing:
  enabled: true
  endpoint: http://127.0.0.1:8767/v1/mobile/heal
  timeoutMs: 4000
  # Optional: bound only the normal lookup before Jev fallback.
  # Omit this to preserve Maestro's standard lookup wait.
  # lookupTimeoutMs: 2000
  maxCandidates: 32
  minConfidence: 0.70
  minMargin: 0.15
---
- tapOn: Sign in
```

Build the local CLI and run the QA pilot after starting the adapter:

```bash
(cd /path/to/maestro-jev-heal && ./gradlew :maestro-cli:installDist)
/path/to/maestro-jev-heal/maestro-cli/build/install/maestro/bin/maestro test app/examples/jev-healing-login.yaml
```

The feature is disabled unless `jevHealing.enabled` is exactly `true`, accepts only loopback HTTP endpoints, and clamps the provider timeout to 100-4,000 ms and candidates to 1-32. The optional `lookupTimeoutMs` setting is clamped to 100-17,000 ms; when it is omitted, the normal Maestro lookup keeps its existing timeout. When it is set, only eligible taps use that bounded lookup before Jev fallback. Sensitive selector and candidate values are redacted for common email, token, password, and authorization forms while safe semantic field labels are retained. The debug metadata contains only fixed status/reason values, bounded candidate IDs, operation and target confidence values, and the redacted selector.

Only Android element taps with no relative point, long press, repeat, retry-on-no-change, or wait-until-visible option are eligible. Assertions, text entry, and point taps never call the adapter; eligibility is determined by the current tap's options. After Jev responds, Maestro refreshes the hierarchy, prefers the original selector if it has appeared, and verifies the selected candidate still matches the fresh bounded hierarchy before tapping. Provider errors, hierarchy failures, unknown candidates, stale candidates, low confidence, and ambiguous margins preserve the original `ElementNotFound` exception. The one successful recovery is recorded under `jevHealing` in `commands.json` when artifact output is enabled.
