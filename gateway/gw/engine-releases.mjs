// Shared by the APK builder and the native launchers.
export const codexRelease = {
  version: "0.166.0", asset: "codex-app-server-aarch64-unknown-linux-musl",
  archiveBytes: 88007340, archiveSha256: "3bc8dce2c2d8df023574736e7439ff834c3825d584dc79aa9b1f642023555bb1",
  bytes: 214949224, sha256: "f21b0b95117d4f32151abe78d8030cc1175e877fe741deaaea46451f2b51ade2"
};
export const codexArchive = `https://github.com/openai/codex/releases/download/rust-v${codexRelease.version}/${codexRelease.asset}.tar.gz`;
export const agyRelease = {
  version: "1.5.13", archive: "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.5.13-5999302598852608/android-arm/cli_android_arm64.tar.gz",
  archiveBytes: 62696677, archiveSha512: "10839b668703ba657fe938e2ebce25e2f39ecba566fbedb87907f6b74dab14e569f50af53ff66b4eaa0b7a708aac330d97ffd95ca4910fcd840d3504bd16d02a",
  bytes: 200758288, sha256: "9eb98c2b808b6e5d0fc5c3e184de66394c2a6c21dd88bfe0bcb9e0762003f1c5"
};
