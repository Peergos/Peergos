#!/bin/bash

##
## To set this as the pre-commit hook do:
##
## ln -s $(git rev-parse --show-toplevel)/scripts/ensure-compile.sh $(git rev-parse --show-toplevel)/.git/hooks/pre-commit
##

echo "********************************"
echo " Checking if project compiles.."
echo "********************************"

if ! ant compile;
then 
    echo "Project failed to compile"
    exit 1
fi

