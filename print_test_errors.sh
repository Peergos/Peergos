#!/bin/bash
#
# Read junit test-reports and print a summary of the error-cases, including the stack trace.
# Will exit with status 1 if there are any errors, otherwise exit status 0.
#  
# By default will scan all files in "./test.reports".
#
# Usage "./print_test_errors.sh <test-report-path>
#
awk '/<(failure|error)/,/\/(failure|error)/ {print prev; has_err=1} {prev=$0} END {exit has_err}'  ${1:-test.reports/*}
