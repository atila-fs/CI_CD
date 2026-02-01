pipeline {
    agent any

    parameters {
        string(name: 'SERVICO', description: 'Nome da pasta do serviço')
    }

    stages {

        // STAGE 1 (APROVAÇÃO)
        stage('Approve') {
            steps {
                script {

                    // Envia email
                    emailext (
                        subject: "Aprovação necessária: Deploy em PRODUÇÃO",
                        to: "[EMAIL_ADDRESS]",
                        body: """
                        A pipeline está aguardando aprovação para continuar o deploy em PRODUÇÃO.

                        Projeto: <PROJETO>
                        Ambiente: PRODUÇÃO
                        Link para aprovar ou rejeitar:
                        ${env.BUILD_URL}input

                        Por favor, revise e aprove ou rejeite.
                        """
                    )

                    // Pausa e aguarda aprovação no Jenkins
                    input(
                        message: "Prosseguir com o deploy em PRODUÇÃO?",
                        ok: "Sim, fazer deploy",
                        submitter: "JENKINS_ADMIN"
                    )
                }
            }
        }

        // STAGE 2 (DEPLOY)
        stage('Deploy remoto via SSH') {
            steps {
                withCredentials([usernamePassword(credentialsId: '<CREDENTIALS_ID>', usernameVariable: 'USER', passwordVariable: 'PASS')]) {
                    sh """
                        export SSHPASS=${PASS}
                        sshpass -e ssh -o StrictHostKeyChecking=no ${USER}@<IP_ADDRESS> "
                            echo '-----------------------------------------------------------'
                            echo '[INFO] Limpando diretório /tmp'
                            rm -rf /tmp/DOCKER_REPO_NAME &&
                            export GIT_SSL_NO_VERIFY=true &&
                            echo '[INFO] Clonando repositório DOCKER_REPO_NAME'
                            git clone <DOCKER_REPO_URL> /tmp/DOCKER_REPO_NAME &&
                            echo '[INFO] Iniciando deploy do serviço: ${params.SERVICO}'
                            cd /opt/<PROJECT_NAME>-<ENVIRONMENT_NAME>/${params.SERVICO} &&
                            docker-compose down &&
                            cd /tmp/DOCKER_REPO_NAME/${params.SERVICO} &&
                            docker-compose up -d &&
                            echo '[SUCESSO] Deploy finalizado com sucesso'
                            echo '-----------------------------------------------------------'
                        "
                    """
                }
            }
        }

        // STAGE 3 (TESTE)
        stage('Teste de Containers') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'Plurio_Sign_PROD', usernameVariable: 'USER', passwordVariable: 'PASS')]) {
                    sh """
                        export SSHPASS=${PASS}
                        sshpass -e ssh -o StrictHostKeyChecking=no ${USER}@<IP_ADDRESS> "
                            echo '-----------------------------------------------------------'
                            echo '[INFO] Validação do estado do container'
                            cd /opt/<PROJECT_NAME>-<ENVIRONMENT_NAME>/${params.SERVICO}
                            echo '[INFO] Aguardando 10 segundos para estabilização...'
                            sleep 10

                            PROJECT_STATUS=\$(docker-compose ps | grep 'Up')

                            echo '-----------------------------------------------------------'

                            if [ -n \"\${PROJECT_STATUS}\" ]; then
                                echo '[SUCESSO] Container está ativo'
                                echo \"\${PROJECT_STATUS}\"
                            else
                                echo '[ERRO] Container está down'
                                exit 1
                            fi

                            echo '-----------------------------------------------------------'
                        "
                    """
                }
            }
        }
    }
}