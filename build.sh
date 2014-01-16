#!/bin/bash
set -xe
test -f configure || ./bootstrap
./create-src-dir-overlay.sh
test -d build || mkdir build
cd build
test -f Makefile || ../configure "$@"
exec make "$@"
