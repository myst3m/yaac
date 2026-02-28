# Bash completion script for yaac (Yet Another Anypoint CLI)
# Install: source this file or copy to /etc/bash_completion.d/yaac

_yaac_completions() {
    local cur prev words cword
    _init_completion || return

    # Global options (available to all commands)
    local global_opts="-o --output-format -H --no-header -d --debug -U --base-url -P --progress -V --http-trace -X --http-trace-detail -Z --no-cache -1 --http1 -h --help"

    # Output format values
    local output_formats="short json edn yaml wide"

    # Base URL values
    local base_urls="https://anypoint.mulesoft.com https://eu1.anypoint.mulesoft.com https://gov.anypoint.mulesoft.com https://jp1.platform.mulesoft.com"

    # All main commands (including aliases)
    local commands="login get list ls upload up deploy dep delete del rm remove create new describe desc update upd download dl configure config cfg auth http logs build a2a mcp clear"

    # --- Subcommands per canonical command ---
    local get_subcmds="org organization env environment app application api api-instance asset proxy gw gateway rtf runtime-fabric rtt runtime-target serv server ps private-space sg secret-group ent entitlement np node-port cont contract capp connected-app ca scope scopes user team conn connection policy pol idp cp client-provider metrics alert"
    local upload_subcmds="asset"
    local deploy_subcmds="app proxy manifest"
    local delete_subcmds="org app api contract cont policy alert gateway gw asset idp-user connected-app capp cp client-provider rtf runtime-fabric ps private-space"
    local create_subcmds="org organization env environment api policy gateway gw invitation invite connected-app cp client-provider ps private-space alert"
    local describe_subcmds="org organization env environment app application asset api connected-app capp cp client-provider gateway gw ps private-space rtf runtime-fabric server serv"
    local update_subcmds="app asset api org connection conn connected-app cp client-provider upstream policy"
    local download_subcmds="proxy api"
    local config_subcmds="ctx context cred credential cc clear-cache"
    local auth_subcmds="code client azure"
    local logs_subcmds="app"
    local a2a_subcmds="init send console task cancel card session clear"
    local mcp_subcmds="init tool tools call session clear"
    local http_subcmds="get post put patch delete"
    local clear_subcmds="org"

    # --- Options per command ---
    local get_opts="-g --group -a --asset -v --version -q --search-term -A --all -F --fields --type --describe --query --start --end --from --duration --aggregation --app-id --api-id --group-by --limit --offset"
    local upload_opts="-g --group -a --asset -v --version -t --asset-type --api-version"
    local deploy_opts="-g --group -a --asset -v --version -q --search-term"
    local deploy_manifest_opts="-n --dry-run --only --scan --config-properties"
    local delete_opts="-g --group -a --asset -v --version -A --all --all-orgs --dry-run --force --hard-delete -M --managed -t --type"
    local clear_opts="--dry-run"
    local create_opts="-g --group -a --asset -v --version -p --parent -e --email -t --teams --team-id --membership-type -n --name --grant-types --scopes --redirect-uris --audience --public --description --issuer --authorize-url --token-url --introspect-url --register-url --registration-auth --client-id --client-secret --timeout --allow-untrusted-certs --region --cidr-block --reserved-cidrs -M --managed --target --channel --runtime-version --size --public-url --forward-ssl --last-mile-security --log-level --forward-logs --upstream-timeout --connection-timeout --config --outbound"
    local describe_opts="-g --group -a --asset -v --version"
    local update_opts="-g --group -a --asset -v --version --scopes --org-scopes --env-scopes --org --env --description --issuer --authorize-url --token-url --introspect-url --client-id --client-secret --allow-client-import --allow-external-client-modification --allow-local-client-deletion --upstream-uri --jwks-url --config"
    local auth_opts="-p --preset --port"
    local logs_opts="-f --follow"
    local http_opts="-i --internal -m --method"
    local a2a_opts="-b --bearer-token"
    local mcp_opts="-L --list"

    # --- Normalize command aliases to canonical name ---
    _yaac_canonical_cmd() {
        case "$1" in
            get|list|ls) echo "get" ;;
            upload|up) echo "upload" ;;
            deploy|dep) echo "deploy" ;;
            delete|del|rm|remove) echo "delete" ;;
            create|new) echo "create" ;;
            describe|desc) echo "describe" ;;
            update|upd) echo "update" ;;
            download|dl) echo "download" ;;
            configure|config|cfg) echo "config" ;;
            auth) echo "auth" ;;
            http) echo "http" ;;
            logs) echo "logs" ;;
            build) echo "build" ;;
            a2a) echo "a2a" ;;
            mcp) echo "mcp" ;;
            clear) echo "clear" ;;
            login) echo "login" ;;
            *) echo "" ;;
        esac
    }

    # Find command and subcommand in the current line
    local cmd="" subcmd="" canonical=""
    local i
    for ((i=1; i < cword; i++)); do
        local w="${words[i]}"
        # Skip options and their values
        [[ "$w" == -* ]] && continue
        if [[ -z "$cmd" ]]; then
            canonical=$(_yaac_canonical_cmd "$w")
            if [[ -n "$canonical" ]]; then
                cmd="$w"
            fi
        elif [[ -z "$subcmd" ]]; then
            subcmd="$w"
        fi
    done

    # Handle option value completions
    case "$prev" in
        -o|--output-format)
            COMPREPLY=($(compgen -W "$output_formats" -- "$cur"))
            return ;;
        -U|--base-url)
            COMPREPLY=($(compgen -W "$base_urls" -- "$cur"))
            return ;;
        --grant-types)
            COMPREPLY=($(compgen -W "client_credentials authorization_code" -- "$cur"))
            return ;;
        --audience)
            COMPREPLY=($(compgen -W "internal everyone" -- "$cur"))
            return ;;
        --type)
            case "$canonical" in
                create) COMPREPLY=($(compgen -W "sandbox production" -- "$cur")) ;;
                delete) COMPREPLY=($(compgen -W "api app server" -- "$cur")) ;;
                get) COMPREPLY=($(compgen -W "app-inbound app-inbound-response-time app-outbound api-path api-summary" -- "$cur")) ;;
            esac
            return ;;
        --region)
            COMPREPLY=($(compgen -W "us-east-1 us-east-2 us-west-1 us-west-2 eu-west-1 eu-west-2 eu-central-1 ap-northeast-1 ap-southeast-1 ap-southeast-2 sa-east-1 ca-central-1" -- "$cur"))
            return ;;
        --channel)
            COMPREPLY=($(compgen -W "edge lts" -- "$cur"))
            return ;;
        --size)
            COMPREPLY=($(compgen -W "small large" -- "$cur"))
            return ;;
        --log-level)
            COMPREPLY=($(compgen -W "debug info warn error" -- "$cur"))
            return ;;
        --aggregation)
            COMPREPLY=($(compgen -W "count sum avg max min" -- "$cur"))
            return ;;
        -m|--method)
            COMPREPLY=($(compgen -W "GET POST PUT PATCH DELETE" -- "$cur"))
            return ;;
        --membership-type)
            COMPREPLY=($(compgen -W "member maintainer" -- "$cur"))
            return ;;
        --config-properties|--config)
            _filedir
            return ;;
        # Options that take a user-supplied value (no completion)
        -g|--group|-a|--asset|-v|--version|-q|--search-term|-F|--fields|-n|--name|\
        --scopes|--org-scopes|--env-scopes|--org|--env|--redirect-uris|--cidr-block|\
        --reserved-cidrs|--upstream|--label|--port|--preset|--description|--issuer|\
        --authorize-url|--token-url|--introspect-url|--register-url|--registration-auth|\
        --client-id|--client-secret|--timeout|--upstream-uri|--jwks-url|\
        --upstream-timeout|--connection-timeout|--target|--runtime-version|--public-url|\
        -p|--parent|-e|--email|-t|--teams|--team-id|--api-version|\
        --describe|--query|--start|--end|--from|--duration|--app-id|--api-id|\
        --group-by|--limit|--offset|--only|-b|--bearer-token|--asset-type)
            return ;;
    esac

    # No command yet → complete commands and global options
    if [[ -z "$cmd" ]]; then
        if [[ "$cur" == -* ]]; then
            COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
        else
            COMPREPLY=($(compgen -W "$commands" -- "$cur"))
        fi
        return
    fi

    # Complete subcommands or options based on canonical command
    case "$canonical" in
        get)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$get_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $get_opts" -- "$cur"))
            fi
            ;;
        upload)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$upload_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $upload_opts" -- "$cur"))
            else
                _filedir '@(jar|zip|raml|yaml|json)'
            fi
            ;;
        deploy)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$deploy_subcmds" -- "$cur"))
            elif [[ "$subcmd" == "manifest" ]]; then
                if [[ "$cur" == -* ]]; then
                    COMPREPLY=($(compgen -W "$global_opts $deploy_manifest_opts" -- "$cur"))
                else
                    _filedir '@(yaml|yml)'
                fi
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $deploy_opts" -- "$cur"))
            else
                _filedir
            fi
            ;;
        delete)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$delete_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $delete_opts" -- "$cur"))
            fi
            ;;
        clear)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$clear_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $clear_opts" -- "$cur"))
            fi
            ;;
        create)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$create_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $create_opts" -- "$cur"))
            fi
            ;;
        describe)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$describe_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $describe_opts" -- "$cur"))
            fi
            ;;
        update)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$update_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $update_opts" -- "$cur"))
            fi
            ;;
        download)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$download_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
            fi
            ;;
        config)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$config_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
            fi
            ;;
        auth)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$auth_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $auth_opts" -- "$cur"))
            fi
            ;;
        logs)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$logs_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $logs_opts" -- "$cur"))
            fi
            ;;
        http)
            if [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $http_opts" -- "$cur"))
            fi
            ;;
        a2a)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$a2a_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $a2a_opts" -- "$cur"))
            fi
            ;;
        mcp)
            if [[ -z "$subcmd" && "$cur" != -* ]]; then
                COMPREPLY=($(compgen -W "$mcp_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $mcp_opts" -- "$cur"))
            fi
            ;;
        build)
            if [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
            else
                COMPREPLY=($(compgen -W "clean compile package install test verify dependency:tree" -- "$cur"))
            fi
            ;;
        login)
            if [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
            fi
            ;;
    esac
}

complete -F _yaac_completions yaac
