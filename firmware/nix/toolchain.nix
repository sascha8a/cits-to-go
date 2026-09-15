{ pkgs }:
let
  downloads = {
    x86_64-linux = {
      arch = "x86_64";
      sha256 = "516abd1c8e9646d8b846f180d7c06d95494822df93aa967e097c6591f6e4d4d6";
    };
    aarch64-linux = {
      arch = "aarch64";
      sha256 = "d38feff577a343a9b0bab2d546e2b0c8ff7e3e6785dec941e8ea567b5c7a23b1";
    };
  };
  download = downloads.${pkgs.stdenv.hostPlatform.system};
in
pkgs.stdenvNoCC.mkDerivation {
  pname = "riscv32-esp-elf";
  version = "15.2.0_20250929";
  # URLs and SHA-256 values are from tools/tools.json at the pinned IDF revision.
  src = pkgs.fetchurl {
    url = "https://github.com/espressif/crosstool-NG/releases/download/esp-15.2.0_20250929/riscv32-esp-elf-15.2.0_20250929-${download.arch}-linux-gnu.tar.xz";
    inherit (download) sha256;
  };
  nativeBuildInputs = [ pkgs.autoPatchelfHook ];
  buildInputs = [ pkgs.stdenv.cc.cc.lib ];
  dontConfigure = true;
  dontBuild = true;
  installPhase = ''
    runHook preInstall
    mkdir -p "$out"
    cp -a . "$out/"
    runHook postInstall
  '';
  # Patching target ELF objects would corrupt embedded libraries. autoPatchelf
  # processes only ELF files for the host architecture.
  dontStrip = true;
  meta.platforms = [ "x86_64-linux" "aarch64-linux" ];
}
