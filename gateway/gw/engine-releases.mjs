// Shared by the APK builder and the native launchers.
export const codexRelease = {
  version: "0.166.0", asset: "codex-aarch64-unknown-linux-musl",
  archiveBytes: 113382496, archiveSha256: "94eb707605ec2646b2e05b7edf7c4e7b3ccf02b16237c34ae9beb8cfb7f810a9",
  bytes: 277549176, sha256: "f2e6c980c937e734644bed1b4a9a018d3e63a0ccbe71aafc85c86a8c6e4b462a"
};
export const codexArchive = `https://github.com/openai/codex/releases/download/rust-v${codexRelease.version}/${codexRelease.asset}.tar.gz`;
export const agyRelease = {
  version: "1.5.13", archive: "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.5.13-5999302598852608/android-arm/cli_android_arm64.tar.gz",
  archiveBytes: 62696677, archiveSha512: "10839b668703ba657fe938e2ebce25e2f39ecba566fbedb87907f6b74dab14e569f50af53ff66b4eaa0b7a708aac330d97ffd95ca4910fcd840d3504bd16d02a",
  bytes: 200758288, sha256: "9eb98c2b808b6e5d0fc5c3e184de66394c2a6c21dd88bfe0bcb9e0762003f1c5"
};
