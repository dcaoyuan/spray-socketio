#!/bin/sh

rm -r target/scala-2.10/dist/bin
rm -r target/scala-2.10/dist/conf
rm -r target/scala-2.10/dist/logs

mkdir target/scala-2.10/dist
cp -r src/main/resources/bin target/scala-2.10/dist/bin
cp -r src/main/resources/conf target/scala-2.10/dist/conf
mkdir target/scala-2.10/dist/logs

