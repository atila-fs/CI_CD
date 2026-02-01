pipeline {
    agent any

    parameters {
        // STRINGS
        string(
            name: 'SERVICE',
            description: 'Nome do serviço conforme está no catálogo do git',
            trim: true
        )

        string(
            name: 'SQUAD',
            description: 'Squad responsável pela publicação',
            trim: true
        )

        string(
            name: 'RDM',
            description: 'Pasta da RDM',
            trim: true
        )
    }

    stages {

        // STAGE 01 APROVAÇÂO
        stage('Approve') {
            steps {
                script {
                    emailext(
                        subject: "Aprovação necessária: Deploy em STAGING",
                        to: "[EMAIL_ADDRESS]",
                        body: """
A pipeline está aguardando aprovação para continuar o deploy em STAGING.

Serviço : ${params.SERVICE}
RDM     : ${params.RDM}
SQUAD   : ${params.SQUAD}

${env.BUILD_URL}input
"""
                    )

                    input(
                        message: "Prosseguir com o deploy em STAGING?",
                        ok: "Sim, fazer deploy",
                        submitter: "JENKINS_ADMIN"
                    )
                }
            }
        }

        // STAGE 02 CATÁLOGO
        stage('Carregar catálogo') {
            steps {
                script {
                    def catalog = readYaml file: 'STAGING/services.yml'
                    def service = catalog.services[params.SERVICE]

                    if (!service) {
                        error "Serviço '${params.SERVICE}' não encontrado no catálogo"
                    }

                    env.ZIP_NAME     = service.zip_name
                    env.DEST_PATH    = service.dest_path
                    env.SQUAD        = params.SQUAD?.trim() ?: service.squad
                    env.ZIP_PATH     = "/sftp/sftp_dev_stg/upload/${env.SQUAD}/${params.RDM}/${env.ZIP_NAME}"

                    // AppPool: usado apenas nas etapas de stop/start
                    env.APPPOOL_NAME = service.apppool_name ?: ""

                    echo """
Deploy planejado:
  Serviço   : ${params.SERVICE}
  Squad     : ${env.SQUAD}
  RDM       : ${params.RDM}
  ZIP       : ${env.ZIP_PATH}
  Destino   : ${env.DEST_PATH}
  AppPool   : ${env.APPPOOL_NAME}
"""
                }
            }
        }

        // STAGE 03 VALIDAÇÂO ZIP TO FTP
        stage('Validar ZIP no FTP') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'STG',
                        usernameVariable: 'ANSIBLE_USER',
                        passwordVariable: 'ANSIBLE_PASS'
                    )
                ]) {
                    sh '''
export SSHPASS="$ANSIBLE_PASS"

sshpass -e ssh -o StrictHostKeyChecking=no "$ANSIBLE_USER@<staging_server_ip>" \
  "test -f \\"$ZIP_PATH\\" || (echo \\"ZIP não encontrado: $ZIP_PATH\\" && exit 1);
   sha256sum \\"$ZIP_PATH\\""
'''
                }
            }
        }

        // STAGE 04 BACKUP
        stage('Backup via Ansible') {
            steps {
                script {
                    def payload = [
                        dest_path    : env.DEST_PATH,
                        zip_path     : env.ZIP_PATH,
                        service_name : params.SERVICE,
                        rdm          : params.RDM
                    ]
                    writeFile file: 'extra_vars.json', text: groovy.json.JsonOutput.toJson(payload)
                }

                withCredentials([
                    usernamePassword(
                        credentialsId: 'STG',
                        usernameVariable: 'ANSIBLE_USER',
                        passwordVariable: 'ANSIBLE_PASS'
                    )
                ]) {
                    sh '''
export SSHPASS="$ANSIBLE_PASS"

sshpass -e scp -o StrictHostKeyChecking=no extra_vars.json "$ANSIBLE_USER@<staging_server_ip>:/tmp/extra_vars.json"

sshpass -e ssh -o StrictHostKeyChecking=no "$ANSIBLE_USER@<staging_server_ip>" \
  "cd /etc/ansible/pipeline-jenkins || exit 1;
   sudo -n /usr/bin/ansible-playbook backup.yml -i web-pss -e @/tmp/extra_vars.json"
'''
                }
            }
        }

        // STAGE 05 APP POOL STOP
        stage('Pool Stop via Ansible') {
            steps {
                script {
                    // Se não tiver apppool no catálogo, não faz nada
                    if (!env.APPPOOL_NAME?.trim()) {
                        echo "Sem apppool_name no catálogo para este serviço. Pulando AppPool Stop."
                        return
                    }

                    def payload = [
                        apppool_name : env.APPPOOL_NAME
                    ]
                    writeFile file: 'extra_vars.json', text: groovy.json.JsonOutput.toJson(payload)
                }

                withCredentials([
                    usernamePassword(
                        credentialsId: 'STG',
                        usernameVariable: 'ANSIBLE_USER',
                        passwordVariable: 'ANSIBLE_PASS'
                    )
                ]) {
                    sh '''
export SSHPASS="$ANSIBLE_PASS"

sshpass -e scp -o StrictHostKeyChecking=no extra_vars.json "$ANSIBLE_USER@<staging_server_ip>:/tmp/extra_vars.json"

sshpass -e ssh -o StrictHostKeyChecking=no "$ANSIBLE_USER@<staging_server_ip>" \
  "cd /etc/ansible/pipeline-jenkins || exit 1;
   ansible-playbook apppool_stop.yml -i iis_hml -e @/tmp/extra_vars.json"
'''
                }
            }
        }

        // STAGE 06 DEPLOY
        stage('Deploy via Ansible') {
            steps {
                script {
                    def payload = [
                        dest_path    : env.DEST_PATH,
                        zip_path     : env.ZIP_PATH,
                        service_name : params.SERVICE,
                        rdm          : params.RDM
                    ]
                    writeFile file: 'extra_vars.json', text: groovy.json.JsonOutput.toJson(payload)
                }

                withCredentials([
                    usernamePassword(
                        credentialsId: 'STG',
                        usernameVariable: 'ANSIBLE_USER',
                        passwordVariable: 'ANSIBLE_PASS'
                    )
                ]) {
                    sh '''
        export SSHPASS="$ANSIBLE_PASS"

        sshpass -e scp -o StrictHostKeyChecking=no extra_vars.json "$ANSIBLE_USER@<staging_server_ip>:/tmp/extra_vars.json"

        sshpass -e ssh -o StrictHostKeyChecking=no "$ANSIBLE_USER@<staging_server_ip>" \
        "cd /etc/ansible/pipeline-jenkins || exit 1;
        sudo -n /usr/bin/ansible-playbook deploy.yml -i web-pss --become -e @/tmp/extra_vars.json"
        '''
                }
            }
        }

        // STAGE 07 APP POOL START
        stage('Pool Start via Ansible') {
            steps {
                script {
                    // Se não tiver apppool no catálogo, não faz nada
                    if (!env.APPPOOL_NAME?.trim()) {
                        echo "Sem apppool_name no catálogo para este serviço. Pulando AppPool Start."
                        return
                    }

                    def payload = [
                        apppool_name : env.APPPOOL_NAME
                    ]
                    writeFile file: 'extra_vars.json', text: groovy.json.JsonOutput.toJson(payload)
                }

                withCredentials([
                    usernamePassword(
                        credentialsId: 'STG',
                        usernameVariable: 'ANSIBLE_USER',
                        passwordVariable: 'ANSIBLE_PASS'
                    )
                ]) {
                    sh '''
export SSHPASS="$ANSIBLE_PASS"

sshpass -e scp -o StrictHostKeyChecking=no extra_vars.json "$ANSIBLE_USER@<staging_server_ip>:/tmp/extra_vars.json"

sshpass -e ssh -o StrictHostKeyChecking=no "$ANSIBLE_USER@<staging_server_ip>" \
  "cd /etc/ansible/pipeline-jenkins || exit 1;
   ansible-playbook apppool_start.yml -i iis_hml -e @/tmp/extra_vars.json"
'''
                }
            }
        }

        // STAGE 08 CONFIRMAÇÃO FINAL / ROLLBACK
        stage('Confirmação final (Rollback se necessário)') {
            steps {
                script {
                    env.RESULTADO_FINAL = input(
                        message: "Publicação do serviço '${params.SERVICE}' em HML foi concluída com sucesso?",
                        ok: "Confirmar",
                        submitter: "JENKINS_ADMIN",
                        parameters: [
                            choice(
                                name: 'RESULTADO',
                                choices: ['SIM', 'NAO'],
                                description: 'SIM = encerra como sucesso | NAO = executa rollback e falha o build'
                            )
                        ]
                    ) as String

                    if (env.RESULTADO_FINAL == 'SIM') {
                        echo "Publicação confirmada como sucesso. Encerrando pipeline."
                    } else {
                        echo "Publicação marcada como NÃO. Rollback será executado."
                    }
                }
            }
        }

        stage('Rollback (somente se NAO)') {
            when {
                expression { return env.RESULTADO_FINAL == 'NAO' }
            }
            steps {
                script {
                    def payload = [
                        dest_path    : env.DEST_PATH,
                        service_name : params.SERVICE,
                        rdm          : params.RDM
                    ]
                    writeFile file: 'extra_vars.json', text: groovy.json.JsonOutput.toJson(payload)
                }

                withCredentials([
                    usernamePassword(
                        credentialsId: 'STG',
                        usernameVariable: 'ANSIBLE_USER',
                        passwordVariable: 'ANSIBLE_PASS'
                    )
                ]) {
                    sh '''
        export SSHPASS="$ANSIBLE_PASS"

        sshpass -e scp -o StrictHostKeyChecking=no extra_vars.json "$ANSIBLE_USER@<staging_server_ip>:/tmp/extra_vars.json"

        sshpass -e ssh -o StrictHostKeyChecking=no "$ANSIBLE_USER@<staging_server_ip>" \
        "cd /etc/ansible/pipeline-jenkins || exit 1;
        sudo -n /usr/bin/ansible-playbook rollback.yml -i web-pss -e @/tmp/extra_vars.json"
        '''
                }

                script {
                    error("Rollback executado a pedido do aprovador. Build marcado como FALHA para auditoria.")
                }
            }
        }

        // STAGE 09 EQUIPARAÇÃO 
        stage('Equiparar para <Prod_Server>') {
            when {
                expression { return env.RESULTADO_FINAL == 'SIM' }
            }
            steps {
                script {
                    def payload = [
                        dest_path: env.DEST_PATH
                    ]
                    writeFile file: 'extra_vars.json', text: groovy.json.JsonOutput.toJson(payload)
                }

                withCredentials([
                    usernamePassword(
                        credentialsId: 'STG',
                        usernameVariable: 'ANSIBLE_USER',
                        passwordVariable: 'ANSIBLE_PASS'
                    )
                ]) {
                    sh '''
export SSHPASS="$ANSIBLE_PASS"

sshpass -e scp -o StrictHostKeyChecking=no extra_vars.json "$ANSIBLE_USER@<prod_server_ip>:/tmp/extra_vars.json"

sshpass -e ssh -o StrictHostKeyChecking=no "$ANSIBLE_USER@<prod_server_ip>" \
  "cd /etc/ansible/pipeline-jenkins || exit 1;
   sudo -n /usr/bin/ansible-playbook equiparar.yml -i web-pss --become -e @/tmp/extra_vars.json"
'''
                }
            }
        }
    }
}