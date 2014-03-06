#!/bin/sh

lock_file=./.lock_benchserver

if [ -f ${lock_file} ]
then
    kill -9 `cat ${lock_file}`
    if [ $? -eq 0 ]
    then
        echo "Stopped `cat ${lock_file}`"
        rm ${lock_file}
        exit 0
    fi
fi
