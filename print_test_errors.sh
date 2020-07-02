#!/bin/bash

awk '/<error/,/\/error/ {print prev} {prev=$0}'  test.reports/*
