#!/bin/sh

lock_files=("./.lock_benchtransport" "./.lock_benchconnectionActive" "./.lock_benchbusiness")

for lock_file in "${lock_files[@]}"
do
    if [ -f ${lock_file} ]
    then
        kill -9 `cat ${lock_file}`
        if [ $? -eq 0 ]
        then
            echo "Stopped `cat ${lock_file}`"
            rm ${lock_file}
        fi
    fi
done
