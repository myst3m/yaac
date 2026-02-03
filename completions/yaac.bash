# Bash completion script for yaac (Yet Another Anypoint CLI)
# Install: source this file or copy to /etc/bash_completion.d/yaac

_yaac_completions() {
    local cur prev words cword
    _init_completion || return

    # Global options
    local global_opts="-o --output-format -H --no-header -d --debug -U --base-url -P --progress -V --http-trace -X --http-trace-detail -Z --no-cache -1 --http1 -h --help"

    # Main commands
    local commands="login get upload deploy delete clear create describe update download config auth http logs"

    # Subcommands for each main command
    local get_subcmds="org organization env environment app application api api-instance asset proxy gw gateway rtf runtime-fabric rtt runtime-target ps private-space serv server ent entitlement np node-port cont contract capp connected-app scopes scope user sg secret-group"
    local create_subcmds="org organization env environment api policy invitation invite connected-app ps private-space"
    local delete_subcmds="org organization app application api asset cont contract capp connected-app cp client-provider idp-user rtf runtime-fabric ps private-space"
    local clear_subcmds="org organization"
    local deploy_subcmds="app application proxy manifest"
    local upload_subcmds="asset"
    local describe_subcmds="org organization env environment app application asset api capp connected-app"
    local update_subcmds="app application asset api org organization conn connection connected-app upstream policy"
    local download_subcmds="proxy api"
    local config_subcmds="ctx context cred credential cc clear-cache"
    local auth_subcmds="azure"
    local logs_subcmds="app application"
    local http_subcmds="get post put patch delete"

    # Output format options
    local output_formats="short json edn yaml"

    # Command-specific options
    local delete_org_opts="--force --dry-run"
    local delete_app_opts="-A --all --dry-run --force --all-orgs"
    local delete_api_opts="-A --all --dry-run --force"
    local delete_asset_opts="-g --group -a --asset -v --version -A --all --dry-run --hard-delete"
    local delete_rtf_opts="-A --all --dry-run --force"
    local delete_ps_opts="-A --all --dry-run --force"
    local clear_org_opts="--dry-run"
    local get_opts="-F --fields -A --all -q --query"
    local create_org_opts="--name --owner-id --entitlements-id"
    local create_env_opts="--name --type"
    local create_ps_opts="--name --region --cidr-block --reserved-cidrs"
    local create_api_opts="-g --group -a --asset -v --version --label --upstream"
    local connected_app_opts="--name --grant-types --scopes --redirect-uris --audience --public --org-scopes --env-scopes --org --env"
    local update_connected_app_opts="--scopes --org-scopes --env-scopes --org --env"
    local deploy_app_opts="-g --group -a --asset -v --version --replicas --vcores --workers --public-url --last-mile-security --forward-ssl-session --runtime-version"
    local upload_asset_opts="-g --group -a --asset -v --version --api-version --classifier --type"
    local logs_opts="-f --follow --lines -n"

    # Environment types for create env
    local env_types="sandbox production"

    # Regions for create ps
    local regions="us-east-1 us-east-2 us-west-1 us-west-2 eu-west-1 eu-west-2 eu-central-1 ap-northeast-1 ap-southeast-1 ap-southeast-2 sa-east-1 ca-central-1"

    # Determine position in command
    local cmd=""
    local subcmd=""
    local i
    for ((i=1; i < cword; i++)); do
        case "${words[i]}" in
            login|get|upload|deploy|delete|clear|create|describe|update|download|config|auth|http|logs)
                cmd="${words[i]}"
                if [[ $((i+1)) -lt $cword ]]; then
                    subcmd="${words[i+1]}"
                fi
                break
                ;;
        esac
    done

    # Complete based on context
    case "$prev" in
        -o|--output-format)
            COMPREPLY=($(compgen -W "$output_formats" -- "$cur"))
            return
            ;;
        -U|--base-url)
            COMPREPLY=($(compgen -W "https://anypoint.mulesoft.com https://eu1.anypoint.mulesoft.com https://gov.anypoint.mulesoft.com https://jp1.platform.mulesoft.com" -- "$cur"))
            return
            ;;
        --grant-types)
            COMPREPLY=($(compgen -W "client_credentials authorization_code password refresh_token" -- "$cur"))
            return
            ;;
        --audience)
            COMPREPLY=($(compgen -W "internal everyone" -- "$cur"))
            return
            ;;
        --type)
            if [[ "$cmd" == "create" && "$subcmd" == "env" ]]; then
                COMPREPLY=($(compgen -W "$env_types" -- "$cur"))
                return
            fi
            ;;
        --region)
            COMPREPLY=($(compgen -W "$regions" -- "$cur"))
            return
            ;;
        -g|--group|-a|--asset|-v|--version|--name|--scopes|--org-scopes|--env-scopes|--org|--env|--redirect-uris|--cidr-block|--reserved-cidrs|--upstream|--label|-F|--fields|-q|--query|-n|--lines)
            # These require user input, no completion
            return
            ;;
    esac

    # If no command yet, complete commands and global options
    if [[ -z "$cmd" ]]; then
        if [[ "$cur" == -* ]]; then
            COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
        else
            COMPREPLY=($(compgen -W "$commands" -- "$cur"))
        fi
        return
    fi

    # Complete subcommands based on main command
    case "$cmd" in
        get)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "get" ]]; then
                COMPREPLY=($(compgen -W "$get_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $get_opts" -- "$cur"))
            fi
            ;;
        create)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "create" ]]; then
                COMPREPLY=($(compgen -W "$create_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                case "$subcmd" in
                    connected-app|capp)
                        COMPREPLY=($(compgen -W "$global_opts $connected_app_opts" -- "$cur"))
                        ;;
                    ps|private-space)
                        COMPREPLY=($(compgen -W "$global_opts $create_ps_opts" -- "$cur"))
                        ;;
                    api)
                        COMPREPLY=($(compgen -W "$global_opts $create_api_opts" -- "$cur"))
                        ;;
                    org|organization)
                        COMPREPLY=($(compgen -W "$global_opts $create_org_opts" -- "$cur"))
                        ;;
                    env|environment)
                        COMPREPLY=($(compgen -W "$global_opts $create_env_opts" -- "$cur"))
                        ;;
                    *)
                        COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
                        ;;
                esac
            fi
            ;;
        delete)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "delete" ]]; then
                COMPREPLY=($(compgen -W "$delete_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                case "$subcmd" in
                    org|organization)
                        COMPREPLY=($(compgen -W "$global_opts $delete_org_opts" -- "$cur"))
                        ;;
                    app|application)
                        COMPREPLY=($(compgen -W "$global_opts $delete_app_opts" -- "$cur"))
                        ;;
                    api|api-instance)
                        COMPREPLY=($(compgen -W "$global_opts $delete_api_opts" -- "$cur"))
                        ;;
                    asset)
                        COMPREPLY=($(compgen -W "$global_opts $delete_asset_opts" -- "$cur"))
                        ;;
                    rtf|runtime-fabric)
                        COMPREPLY=($(compgen -W "$global_opts $delete_rtf_opts" -- "$cur"))
                        ;;
                    ps|private-space)
                        COMPREPLY=($(compgen -W "$global_opts $delete_ps_opts" -- "$cur"))
                        ;;
                    *)
                        COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
                        ;;
                esac
            fi
            ;;
        clear)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "clear" ]]; then
                COMPREPLY=($(compgen -W "$clear_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $clear_org_opts" -- "$cur"))
            fi
            ;;
        deploy)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "deploy" ]]; then
                COMPREPLY=($(compgen -W "$deploy_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $deploy_app_opts" -- "$cur"))
            else
                # Complete file/directory names for deploy app
                _filedir
            fi
            ;;
        upload)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "upload" ]]; then
                COMPREPLY=($(compgen -W "$upload_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $upload_asset_opts" -- "$cur"))
            else
                # Complete file names for asset upload
                _filedir '@(jar|zip|raml|yaml|json)'
            fi
            ;;
        describe)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "describe" ]]; then
                COMPREPLY=($(compgen -W "$describe_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
            fi
            ;;
        update)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "update" ]]; then
                COMPREPLY=($(compgen -W "$update_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                case "$subcmd" in
                    connected-app|capp)
                        COMPREPLY=($(compgen -W "$global_opts $update_connected_app_opts" -- "$cur"))
                        ;;
                    *)
                        COMPREPLY=($(compgen -W "$global_opts -g --group -a --asset -v --version" -- "$cur"))
                        ;;
                esac
            fi
            ;;
        download)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "download" ]]; then
                COMPREPLY=($(compgen -W "$download_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
            fi
            ;;
        config)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "config" ]]; then
                COMPREPLY=($(compgen -W "$config_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
            fi
            ;;
        auth)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "auth" ]]; then
                COMPREPLY=($(compgen -W "$auth_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
            fi
            ;;
        logs)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "logs" ]]; then
                COMPREPLY=($(compgen -W "$logs_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts $logs_opts" -- "$cur"))
            fi
            ;;
        http)
            if [[ -z "$subcmd" ]] || [[ "$cur" != -* && "${words[cword-1]}" == "http" ]]; then
                COMPREPLY=($(compgen -W "$http_subcmds" -- "$cur"))
            elif [[ "$cur" == -* ]]; then
                COMPREPLY=($(compgen -W "$global_opts" -- "$cur"))
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
