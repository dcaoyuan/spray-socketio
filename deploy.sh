#!/bin/sh

rm -r target/scala-2.10/bin
rm -r target/scala-2.10/conf

cp -r src/main/resources/bin target/scala-2.10/bin
cp -r src/main/resources/conf target/scala-2.10/conf

