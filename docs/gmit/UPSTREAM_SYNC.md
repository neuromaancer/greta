# Upstream master integration

Date: 2026-10-26. Scope: synchronize the development repository's master and
`feat/gmit-multiparty`, preserving upstream history and existing feature work.
The complete `greta_2.1` runtime and private GMIT repository are not updated by
this merge.

## History

| Ref before integration | Commit | Relationship |
| --- | --- | --- |
| Fork master | `4a469dc2` | 57 commits behind official master; no unique commits |
| Official master | `ee52762c6ed685ca98ead7bf9cfc49ae9a4a3949` | Synchronization target |
| Feature branch | `6e24f78f5209` | Greta 2.1 integration, README update, and GMIT P0/P1 |
| Common ancestor of feature and official master | `89e6052c` | Feature needs 69 upstream commits |

The fork master was fast-forwarded and pushed unchanged. The feature branch
merges that master while retaining the earlier Greta 2.1 integration and README
commits.
`origin` remains `neuromaancer/greta`; `upstream` is `isir/greta`.

## Conflict decisions

The merge initially had 144 unmerged paths. Most came from the independently
introduced Greta 2.0/2.1 modules and legacy resources, rather than the new GMIT
scene. Resolutions preserve both branches' intended features:

- Combine line-ending rules with upstream Linguist attributes, and combine
  ignore rules. Retain the Greta 2.1 README and add upstream directory guidance
  and the Greta 2.0 release notes.
- Merge the Modular registry so upstream modules coexist with `MODIFF_VQ`,
  TurnManagement WoZ/Model and InterruptionClicker. Preserve the feature's
  clock-driven face/lip blending, monotonic frames, expired lip cleanup and
  eyelid protection. The Realizer conflict changes comments only.
- Retain feature ASR duplicate suppression, asynchronous dispatch and Swing EDT
  updates, the existing microphone selection, and the paired OpenFace GUI/source
  containing Sam VAP and MODIFF-VQ controls. Accept upstream build metadata and
  compile settings where they do not remove feature dependencies.
- Resolve legacy MI Python conflicts to the upstream implementation compatible
  with its pinned Mistral SDK. Resolve legacy MODIFF conflicts to the upstream
  source/assets; keep the feature's Java behavior where the alternative only
  restores debug logging. Feature-only Sam VAP, MODIFFVQ and RealTimeMod content
  remains present. These advanced pipelines are outside the GMIT P1 scene.
- Keep upstream renamed incremental/RL demo configurations and the existing
  non-incremental/backchannel variants at their distinct paths. Preserve active,
  backup and sub backchannel examples rather than losing one side of the rename.
- Keep four existing nonempty `api_key.txt` files local and ignored, removing
  them from the feature branch index. Exclude 20 conflicting generated Python
  cache files from the index while retaining their local files. This does not
  erase existing Git history or modify official/fork master contents. Other
  already tracked upstream resources are not subject to a general cleanup.

## Verification boundaries

The eight files in `integrations/gmit/deployment.json` have the same Git blobs
as before this merge. No new runtime JARs are built or deployed, and earlier
human audiovisual acceptance remains evidence for the existing runtime only.

The resolved Realizer, FaceBlender and LipBlender compile with the existing
Windows `javac 1.8.0_111`, targeting Java 8 and reading runtime JARs as dependencies.
Class output stays under ignored `bin/GMIT/local/merge-check/`. The GMIT scene
has 42 module instances and 44 connections with no dangling endpoints; all
15 required module types and 10 connector types exist in the merged registry.
The registry's two duplicate ActiveMQ environment connectors also exist in
upstream master and are not introduced here.

Eight auxiliary entry classes also compile with JDK 8: ASAPFrame, DeepGramFrame,
MICounselorIncrementalDA, MICounselorRL, MODIFFFrame, OpenFaceOutputStreamReader,
TurnManagement and TurnManagementFrame. Dependencies come from the existing
runtime JARs and current source roots; generated classes stay in the same ignored
validation directory. The six resolved/current advanced demo variants parse,
reference registered modules and have no dangling connections. All twelve
backchannel rename variants parse successfully.

Conflict Python sources pass syntax parsing, and resolved GUI forms have unique
component names. NetBeans generated-file CRC entries match their merged project
and build XML. Authored instructions/docs pass whitespace checks; imported
upstream source retains pre-existing whitespace diagnostics rather than being
mass-reformatted.

The restored old incremental backchannel example refers to
`DeepGramWithBackchannel`, which is absent from both parents' registries. It is
retained as an existing example, not reported as runnable. No full NetBeans build,
live LLM, ASR/VAP/MODIFF execution, model loading, or new GUI/audio acceptance is
claimed for the merged source tree.

Future completed changes use focused Conventional Commits and are pushed after
appropriate verification under the user's standing authorization. Read-only
questions do not require commits; explicit later scope restrictions take priority.
