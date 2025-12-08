# Repository Agent Notes

- Default test runner: `./mill -i corvus.test` or `./mill -i corvus.test.testOnly <suite>`.
- When running tests under sandboxed environments, disable ccache to avoid permission errors from `/home/*/.cache/ccache`:  
  `CCACHE_DISABLE=1 ./mill -i corvus.test.testOnly corvus.ctrl_axi4_slave.CtrlAXI4SlaveSpec`
- If you prefer to keep ccache, point it to a writable directory, e.g. `sim-build/.ccache`:  
  `env CCACHE_DIR=$PWD/sim-build/.ccache CCACHE_TEMPDIR=$PWD/sim-build/.ccache/tmp ./mill -i corvus.test`
