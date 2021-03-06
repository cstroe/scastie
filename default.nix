let
  pkgs = import <nixpkgs> {};
  stdenv = pkgs.stdenv;
  sbt = pkgs.callPackage sbt.nix { };
  jre = pkgs.jre;
  fetchurl = pkgs.fetchurl;
in rec {
  scastie = stdenv.mkDerivation rec {
    SBT_OPTS = "-Xms512m -Xmx1024M";
    name = "sbt-env";
    shellHook = ''
    alias cls=clear
    '';
    buildInputs = [
      pkgs.openjdk
      sbt
      pkgs.nodejs
      pkgs.yarn
    ];
  };

  sbt = stdenv.mkDerivation rec {
    name = "sbt-${version}";
    version = "1.3.7";
 
    src = fetchurl {
      url = "https://piccolo.link/sbt-1.3.7.tgz";
      sha256 = "813d4a3b7d2f9d8e5585d959fd5bc389c999770d5b6f2b9c313cc009f7729814";
      name = "sbt.tgz";
    };

    patchPhase = ''
      echo -java-home ${jre.home} >>conf/sbtopts
    '';

    installPhase = ''
      mkdir -p $out/share/sbt $out/bin
      cp -ra . $out/share/sbt
      ln -s $out/share/sbt/bin/sbt $out/bin/
    '';

    meta = with stdenv.lib; {
      homepage = http://www.scala-sbt.org/;
      license = licenses.bsd3;
      description = "A build tool for Scala, Java and more";
      maintainers = with maintainers; [ rickynils ];
      platforms = platforms.unix;
    };
  };

}