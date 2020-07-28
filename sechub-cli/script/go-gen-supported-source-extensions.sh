#!/bin/bash

# SPDX-License-Identifier: MIT

SOURCE=supported-source-extensions.txt
# This script is called by gen_go.sh which is called by gradle genGo task.
#
# All commands are also available at a git bash installation on Windows , so we are
# still cross plattform compatible for builds etc.

cd `dirname $0`
cat - <<EOF
// SPDX-License-Identifier: MIT

package cli

/* Attention!
======================== 
DO NOT CHANGE THIS FILE!
======================== 
"constants_filepatterns_gen.go" is automatically generated by gradle genGo task


To avoid compile errors etc. when developers aren't aware about the generation and just doing
an import of the project into their IDE, we decided to NOT add to .gitgnore, but add this code
to git repository even when its generated  */

// DefaultZipAllowedFilePatterns - Defines file patterns to include in zip file.
// These patterns are considered as source code to be scanned.*/
var DefaultZipAllowedFilePatterns = []string{
EOF

RESPONSE=""
SRC=`cat $SOURCE`
while read line ; do
  SRCLANG=`echo $line | cut -d : -f 1`
  SRCEXTLIST=`echo $line | cut -d : -f 2`
  RESPONSE="$RESPONSE    "
  for i in $SRCEXTLIST ; do
    RESPONSE="$RESPONSE\"$i\", "
  done
  RESPONSE="$RESPONSE/* $SRCLANG */"$'\n'
done <<< "$SRC"

echo -n "$RESPONSE" | head -n -1  # all but last line
echo -n "$RESPONSE" | tail -1 | sed 's/\(.*\),/\1}/'  # last line