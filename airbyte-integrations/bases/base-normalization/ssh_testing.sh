CONFIG_FILE="./secrets/sshconfig_pass.json"
# CONFIG_FILE="./secrets/sshconfig_key.json"

tunnel_method=$(cat ${CONFIG_FILE} | jq -r '.tunnel_method.tunnel_method' | tr '[:lower:]' '[:upper:]')
# it's fine if these come out as nulls (don't exist in config), we'll only be using them when they should have values
tunnel_username=$(cat ${CONFIG_FILE} | jq -r '.tunnel_method.tunnel_username')
tunnel_localport=$(cat ${CONFIG_FILE} | jq -r '.tunnel_method.tunnel_localport')
tunnel_db_remote_host=$(cat ${CONFIG_FILE} | jq -r '.tunnel_method.tunnel_db_remote_host')
tunnel_db_remote_port=$(cat ${CONFIG_FILE} | jq -r '.tunnel_method.tunnel_db_remote_port')
tunnel_host=$(cat ${CONFIG_FILE} | jq -r '.tunnel_method.tunnel_host')

tmpcontrolsocket="/tmp/sshsocket${tunnel_db_remote_port}"

if [[ ${tunnel_method} = "SSH_KEY_AUTH" ]] ; then
    # create a temporary file to hold ssh key with non-descript + random name and trap to delete on EXIT
    trap 'rm -f "$tmpkeyfile"' EXIT
    tmpkeyfile=$(mktemp /tmp/xyzfile.XXXXXXXXXXX) || exit 1
    cat ${CONFIG_FILE} | jq -r '.tunnel_method.tunnel_usersshkey' | > $tmpkeyfile

    # -f=background  -N=no remote command  -M=master mode  -S=location of control socket  <- use this so we can close this background process
    trap 'rm -f "$tmpcontrolsocket"' EXIT
    ssh -f -N -M -S $tmpcontrolsocket -i $tmpkeyfile  -L ${tunnel_localport}:${tunnel_db_remote_host}:${tunnel_db_remote_port} ${tunnel_host}
    echo "opened ssh tunnel with key auth"
    # remove temp file as soon as we're done
    rm -f $tmpkeyfile

elif [[ ${tunnel_method} = "SSH_PASSWORD_AUTH" ]] ; then
    # put ssh password in env var for use in sshpass. Better than directly passing with -p but key is always preferred
    export SSHPASS=$(cat ${CONFIG_FILE} | jq -r '.tunnel_method.tunnel_userpass')

    # -f=background  -N=no remote command  -M=master mode  -S=location of control socket  <- use this so we can close this background process
    trap 'rm -f "$tmpcontrolsocket"' EXIT
    sshpass -e ssh -f -N -M -S $tmpcontrolsocket -l ${tunnel_username} -L ${tunnel_localport}:${tunnel_db_remote_host}:${tunnel_db_remote_port} ${tunnel_host}
    echo "opened ssh tunnel with password auth"
fi

# DO STUFF ...

# Use our tmpcontrolsocket to close this specific ssh session
ssh -S $tmpcontrolsocket -O exit ${tunnel_host}
echo "closed ssh tunnel"
