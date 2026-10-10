# Asar 1.81 distribution notice

SMEDIT invokes the standalone Asar executable as a separate process to compile Project ASM
workspaces. Release packages contain a native build of the exact upstream revision below so users
do not need Git, CMake, or a C++ compiler.

- Project: [RPGHacker/asar](https://github.com/RPGHacker/asar)
- Version: 1.81
- Revision: `a8538ca8582cdc81de6941223b358aa851e3b7b1`
- Upstream source: <https://github.com/RPGHacker/asar/tree/a8538ca8582cdc81de6941223b358aa851e3b7b1>
- License: GNU General Public License, version 3

The packaged toolchain directory also contains upstream's unmodified `license-gpl.txt` as
`LICENSE-GPL-3.0.txt`. SMEDIT verifies the executable's version banner before every compiler
session. Asar remains a separate program; SMEDIT communicates with it through command-line files
and does not link against its library interface.
