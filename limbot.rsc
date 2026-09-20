# --- XOR FUNCTION ---
:local xorEncrypt do={
    :local text $1
    :local key $2
    :if ([:len $key] = 0) do={
        :error "XOR key is empty"
    }
    :local textBytes [:convert from=raw to=byte-array $text]
    :local keyBytes [:convert from=raw to=byte-array $key]
    :local result ({})
    :local keyLen [:len $keyBytes]
    :local keyPos 0
    :foreach b in=$textBytes do={
        :local k ($keyBytes->$keyPos)
        :local x ($b ^ $k)
        :set result ($result, $x)
        :set keyPos ($keyPos + 1)
        :if ($keyPos >= $keyLen) do={
            :set keyPos 0
        }
    }
    :return [:convert from=byte-array to=base64 $result]
}

# Chat name
:local routerName "gcore"
# regex ^[a-z0-9]{3,20}$
:local botLogin "gcorebot"
:local botPassword "gcore_bot_password"
:local certName "44limcert.crt"
:local serverIp "192.168.10.3"
:local myIp "192.168.88.2"
# Recipients (comma-separated WITHOUT spaces)
:local interlocutors "redmi,huawei"
# Message text or $message from logs
:local message "ALARM: Ether1 link down!"
:local logins ""

:foreach r in=[:toarray $interlocutors] do={
    :if ([:len $logins] > 0) do={
        :set logins ($logins . ",")
    }
    :set logins ($logins . "\"" . $r . "\"")
}

:local encPass [$xorEncrypt $botPassword $certName]
:local encMsg [$xorEncrypt $message $certName]
# --- ASSEMBLE REQUEST BODY ---
:local payload "{\"displayName\":\"$routerName\",\"username\":\"$botLogin\",\"password\":\"$encPass\",\"text\":\"$encMsg\",\"loginList\":[$logins]}"

# --- SEND AND PROCESS RESPONSE ---
:do {
    :local result [/tool fetch url=("https://" . $serverIp . "/script") src-address=$myIp http-method=post http-data=$payload \
        http-header-field="Content-Type: application/json" check-certificate=no output=user as-value]
    :local responseText ($result->"data")
    :log info ("LimServer Response: " . $responseText)
} on-error={
    :log error "LimServer: Connection failed (Server down or DNS error)"
}