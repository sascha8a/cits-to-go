{
  description = "CITS-to-go ESP32-C5 Rust/Embassy build and serial flash environment";

  # Immutable revisions also pin first use, before flake.lock is generated.
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/5dfba6236110080a54247d6460bc2ff5dda939cc";
    rust-overlay = {
      url = "github:oxalica/rust-overlay/6a84c41e705533dcc5569ac0731f18406b4cdf86";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    esp-idf = {
      url = "git+https://github.com/espressif/esp-idf.git?rev=f21b4c238152dc9e3a24fbad9afe33a3d15f6cfd&submodules=1&shallow=1";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, rust-overlay, esp-idf }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
      perSystem = system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ rust-overlay.overlays.default ];
          };
          rust = pkgs.rust-bin.stable."1.88.0".minimal.override {
            extensions = [ "rustfmt" "clippy" ];
            targets = [ "riscv32imac-unknown-none-elf" ];
          };
          gcc = import ./nix/toolchain.nix { inherit pkgs; };
          sdk = pkgs.runCommand "esp-idf-f21b4c238152" { } ''
            mkdir -p "$out"
            cp -rs ${esp-idf}/. "$out/"
            # Source inputs omit .git; IDF's version fallback is version.txt.
            rm -f "$out/version.txt"
            echo 'v6.1-dev-f21b4c238152' > "$out/version.txt"
          '';
          python = pkgs.python312;
          requirements = ./nix/requirements.lock;
          pythonKey = builtins.substring 0 16
            (builtins.hashString "sha256" "${python}:${builtins.readFile requirements}");
          command = name: script: pkgs.writeShellApplication {
            inherit name;
            text = ''
              if [[ ! -f Cargo.toml || ! -f main/platform.c ]]; then
                echo 'Run this command from the firmware-rs directory.' >&2
                exit 2
              fi
              exec bash "./scripts/${script}" "$@"
            '';
          };
          build = command "cits-build" "build.sh";
          flash = command "cits-flash" "flash.sh";
          app = name: {
            type = "app";
            program = "${pkgs.writeShellApplication {
              name = "cits-${name}-app";
              runtimeInputs = [ pkgs.nix ];
              text = ''
                # A bare /nix/store source path is treated as a store installable.
                # Select the flake's development shell explicitly instead.
                exec nix --extra-experimental-features 'nix-command flakes' \
                  develop "path:${self}#devShells.${system}.default" \
                  --command cits-${name} "$@"
              '';
            }}/bin/cits-${name}-app";
          };
        in {
          shell = pkgs.mkShell {
            packages = [
              rust gcc python pkgs.git pkgs.cmake pkgs.ninja pkgs.pkg-config
              pkgs.gnumake pkgs.flex pkgs.bison pkgs.gperf pkgs.dfu-util
              pkgs.coreutils build flash
            ];
            IDF_PATH = "${sdk}";
            CITS_NIX_PYTHON = "${python}/bin/python3";
            CITS_PYTHON_REQUIREMENTS = "${requirements}";
            CITS_PYTHON_BOOTSTRAP = "${./nix/bootstrap.lock}";
            CITS_PYTHON_KEY = pythonKey;
            # manylinux wheels need the host C++ runtime on NixOS.
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [ pkgs.stdenv.cc.cc.lib ];
            shellHook = ''
              source ${./nix/activate.sh} || exit 1
            '';
          };
          apps = { build = app "build"; flash = app "flash"; default = app "build"; };
          packages.toolchain = gcc;
        };
    in {
      devShells = forAllSystems (system: { default = (perSystem system).shell; });
      apps = forAllSystems (system: (perSystem system).apps);
      packages = forAllSystems (system: (perSystem system).packages);
    };
}
