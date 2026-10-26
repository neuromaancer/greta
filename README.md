<p align="center">
  <img src="https://user-images.githubusercontent.com/54807091/88184824-7c5ba280-cc33-11ea-875e-0a785d95075f.png" alt="Greta logo" width="640">
</p>

<h1 align="center">Greta</h1>

<p align="center">
  <strong>Real-time socio-emotional virtual characters</strong>
</p>

<p align="center">
  <a href="https://github.com/isir/greta/releases/tag/v2.1.0">Greta 2.1</a> ·
  <a href="https://github.com/isir/greta/wiki/Quick-start">Quick start</a> ·
  <a href="https://github.com/isir/greta/wiki">Documentation</a> ·
  <a href="https://youtu.be/uzi6gprzN3c">Watch the 2.0 demo</a>
</p>

Greta is an open-source, modular platform for building **embodied conversational agents**. It generates speech and nonverbal behaviors—including facial expressions, gaze, and gestures—to support real-time interaction with human users.

The platform combines behavior planning and animation with modules for speech recognition, large language models, and turn management. It brings together research projects led by **Catherine Pelachaud** and her team.

## Get started

| I want to… | Start here |
| --- | --- |
| See Greta in action | [Greta 2.0 demo video](https://youtu.be/uzi6gprzN3c), also listed in the [Wiki](https://github.com/isir/greta/wiki#demonstration) |
| Install and run the platform | [Quick start](https://github.com/isir/greta/wiki/Quick-start) |
| Reproduce the Greta 2.0 demo | [Demo setup: LLM, ASR, turn management, and facial expressions](https://github.com/isir/greta/wiki/Greta-2.0:-LLM,-ASR,-TurnManager,-Facial-expression-generator) |
| Try the new Greta 2.1 modules | [Release notes](https://github.com/isir/greta/releases/tag/v2.1.0) and the [`greta_2.1` branch](https://github.com/isir/greta/tree/greta_2.1) |
| Explore modules and examples | [Wiki](https://github.com/isir/greta/wiki) |

> **Choose your version:** Greta 2.1 is available as [`v2.1.0`](https://github.com/isir/greta/releases/tag/v2.1.0) and on the [`greta_2.1` branch](https://github.com/isir/greta/tree/greta_2.1). The default `master` branch currently contains Greta 2.0.

**Requirements:** Greta's Java application is designed for **Java 8**, primarily on **Windows**. Speech synthesis uses external software such as MaryTTS or CereProc, and 3D rendering uses Ogre or Unity. Additional dependencies and model assets depend on the modules you enable; follow the relevant Wiki pages and release notes.

## Updates

### October 12, 2026 — Greta 2.1

Greta 2.1 integrates **Sam VAP turn-taking** and **MODIFF VQ-VAE facial generation** into a shared real-time interaction pipeline.

- **Sam VAP:** reactive turn management with head nods, verbal backchannels, and turn shifts, with both multimodal and audio-only launch options.
- **MODIFF VQ-VAE:** continuous facial behavior generation conditioned on OpenFace facial features, with optional audio and dialogue-act inputs.
- **Shared OpenFace input:** turn management and facial generation can use the same incoming feature stream.
- **Facial animation controls:** temporal smoothing, per-action-unit amplification, and optional coactivation.

[Read the full release notes](https://github.com/isir/greta/releases/tag/v2.1.0) for setup details, launchers, and model requirements. **Sam VAP checkpoints are distributed separately and are not included in Git.**

<details>
<summary><strong>Earlier updates — Greta 2.0 and the 2024 release</strong></summary>

### June 8, 2025 — Greta 2.0

- Added **LipBlender** and **FaceBlender**, allowing the SAIBA behavior pipeline and neural frame-by-frame generation to run in parallel.
- Added **DeepGram ASR** and **Mistral LLM** modules.
- Added **VAP-based turn management** and **VAD-based backchannels**.
- Added facial expression generation based on **MODIFF-8**.

[Watch the Greta 2.0 demo](https://youtu.be/uzi6gprzN3c) · [Follow the demo setup guide](https://github.com/isir/greta/wiki/Greta-2.0:-LLM,-ASR,-TurnManager,-Facial-expression-generator)

### September 3, 2024 — License change and new functionality

- Changed the `master` branch license from LGPL v3 to **GPL v3**. The legacy LGPL version is now available in [`isir/greta-lgpl`](https://github.com/isir/greta-lgpl).
- Added an incremental behavior realizer and Mistral-based LLM processing, including incremental generation.
- Added **DeepASR** for incremental speech recognition.
- Added **MeaningMiner** and **NVBG** (nonverbal behavior generation).
- Supported **English and French** at the time of this update.

</details>

## For developers

Start by running Greta with the [Quick start guide](https://github.com/isir/greta/wiki/Quick-start), then explore the developer guides and module documentation in the [Wiki](https://github.com/isir/greta/wiki).

### Repository structure

| Directory | Contents |
| --- | --- |
| [`application/`](application) | The Modular application, which connects Greta's modules. |
| [`auxiliary/`](auxiliary) | Additional modules and integrations. |
| [`bin/`](bin) | Compiled JARs, launchers, configurations, and supporting resources. |
| [`core/`](core) | Core behavior planning, realization, and animation modules. |
| [`docs/`](docs) | Project documents and papers. |
| [`pictures/`](pictures) | Images used in documentation; some Wiki images are hosted separately. |
| [`tools/`](tools) | Supporting tools and utilities. |

For a first look at the code, open [BehaviorPlanner](core/BehaviorPlanner/src/greta/core/behaviorplanner/Planner.java) and [BehaviorRealizer](core/BehaviorRealizer/src/greta/core/behaviorrealizer/Realizer.java) in NetBeans. The [technical specifications](https://github.com/isir/greta/wiki/Technical-Specifications#name-correspondence-between-greta-modules-and-java-projects) map Greta's modules to their Java projects.

## License

The `master` branch is licensed under the **GNU GPL v3**; see [LICENSE](LICENSE).

The legacy **GNU LGPL v3** version is available in [`isir/greta-lgpl`](https://github.com/isir/greta-lgpl). It predates September 2024 and does not include all current functionality.
