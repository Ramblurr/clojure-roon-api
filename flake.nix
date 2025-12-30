{
  description = "dev env";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
    devshell.url = "github:numtide/devshell";
    devenv.url = "https://flakehub.com/f/ramblurr/nix-devenv/*";
  };
  outputs =
    {
      self,
      devenv,
      devshell,
      ...
    }:
    devenv.lib.mkFlake ./. {
      nixpkgs.config.allowUnfree = true;
      withOverlays = [
        devshell.overlays.default
        devenv.overlays.default
      ];
      devShell =
        pkgs:
        pkgs.devshell.mkShell {
          imports = [
            devenv.capsules.base
            devenv.capsules.clojure
          ];
          # https://numtide.github.io/devshell
          commands = [
            { package = pkgs.roon-server; }
          ];
          packages = [
            (pkgs.python311.withPackages (
              ps: with ps; [
                leveldb
                plyvel
              ]
            ))
          ];
        };
    };
}
