pipeline {
    agent any

    options {
        disableConcurrentBuilds()
    }

    parameters {
        string(
            name: 'GIT_BRANCH',
            defaultValue: 'main',
            description: '배포할 Git 브랜치 (기본: main)'
        )
        booleanParam(
            name: 'ROLLBACK',
            defaultValue: false,
            description: '이전 버전 롤백 여부 (체크 시 빌드 없이 ROLLBACK_TAG 이미지로 즉시 무중단 롤백)'
        )
        reactiveChoice(
            name: 'ROLLBACK_TAG',
            description: '롤백할 Docker 이미지 태그 - ROLLBACK 체크 시 필수',
            choiceType: 'PT_SINGLE_SELECT',
            referencedParameters: 'ROLLBACK',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: false,
                    script: '''
                        def tags = "docker images tcine --format {{.Tag}}".execute().text.trim().split("\\n")
                        return tags.findAll { it.startsWith("prod-") }.sort { a, b ->
                            def aNum = a.replaceAll("[^0-9]", "").toInteger()
                            def bNum = b.replaceAll("[^0-9]", "").toInteger()
                            bNum <=> aNum
                        }
                    '''
                ],
                fallbackScript: [
                    classpath: [],
                    sandbox: false,
                    script: 'return ["태그 조회 실패"]'
                ]
            ]
        )
    }

    environment {
        SERVICE_NAME   = "tcine"
        DEPLOY_DIR     = "/data/tcine"
        NGINX_CONTAINER = "tcine-nginx"
    }

    stages {
        stage('🧹 Cleanup Workspace') {
            when { expression { !params.ROLLBACK } }
            steps {
                cleanWs()
            }
        }

        stage('🔄 Rollback Setup') {
            when { expression { params.ROLLBACK == true } }
            steps {
                script {
                    if (!params.ROLLBACK_TAG?.trim()) {
                        error "❌ ROLLBACK_TAG를 선택하세요 (예: prod-12)"
                    }
                    env.DOCKER_TAG = params.ROLLBACK_TAG.trim()
                    sh "docker tag ${SERVICE_NAME}:${env.DOCKER_TAG} ${SERVICE_NAME}:latest"
                    echo "✅ 롤백 이미지 준비 완료: ${SERVICE_NAME}:${env.DOCKER_TAG}"
                }
            }
        }

        stage('🔍 Checkout') {
            when { expression { !params.ROLLBACK } }
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT_SHORT = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                    echo "✅ Git Commit: ${env.GIT_COMMIT_SHORT}"
                }
            }
        }

        stage('🐳 Docker Build') {
            when { expression { !params.ROLLBACK } }
            steps {
                script {
                    env.DOCKER_TAG = "prod-${BUILD_NUMBER}"
                    echo "🐳 Docker Build (A1 Native ARM64): ${SERVICE_NAME}:${env.DOCKER_TAG}"
                }
                sh """
                    docker build -t ${SERVICE_NAME}:${DOCKER_TAG} .
                    docker tag ${SERVICE_NAME}:${DOCKER_TAG} ${SERVICE_NAME}:latest

                    # 오래된 빌드 이미지 정리 (최근 3개만 유지)
                    docker images ${SERVICE_NAME} --format '{{.Tag}}' | grep '^prod-' | \
                        sort -t'-' -k2 -n | head -n -3 | \
                        xargs -I{} docker rmi ${SERVICE_NAME}:{} || true

                    docker image prune -f || true
                """
                echo "✅ Docker 이미지 빌드 완료: ${SERVICE_NAME}:${env.DOCKER_TAG}"
            }
        }

        stage('🚀 Blue-Green Deploy & Switch') {
            steps {
                script {
                    // 1. 현재 Nginx가 바라보고 있는 활성 슬롯 확인
                    def currentSlot = sh(
                        script: """
                            if [ -f "${DEPLOY_DIR}/nginx/service-url.inc" ]; then
                                awk '{print \$3}' ${DEPLOY_DIR}/nginx/service-url.inc | tr -d ';'
                            elif docker ps --format '{{.Names}}' | grep -q '^${SERVICE_NAME}-blue\$'; then
                                echo "${SERVICE_NAME}-blue"
                            elif docker ps --format '{{.Names}}' | grep -q '^${SERVICE_NAME}-green\$'; then
                                echo "${SERVICE_NAME}-green"
                            else
                                echo ""
                            fi
                        """,
                        returnStdout: true
                    ).trim()

                    def newSlot = (currentSlot == "${SERVICE_NAME}-blue") ? "${SERVICE_NAME}-green" : "${SERVICE_NAME}-blue"
                    echo "🔄 무중단 슬롯 전환 준비: ${currentSlot ?: '없음'} → ${newSlot}"

                    // 2. 배포용 컴포즈 파일 동기화 및 새 슬롯 컨테이너 기동
                    sh """
                        mkdir -p ${DEPLOY_DIR}
                        cp deploy/docker-compose.app.yml ${DEPLOY_DIR}/docker-compose.app.yml
                        docker rm -f ${newSlot} || true
                        cd ${DEPLOY_DIR} && IMAGE_TAG=${env.DOCKER_TAG} docker compose -f docker-compose.app.yml up -d ${newSlot}
                    """

                    // 3. 새 슬롯 Health Check (/actuator/health 기반 Docker HEALTHCHECK)
                    echo "⏳ [${newSlot}] Health Check 시작..."
                    def success = false
                    for (int i = 1; i <= 15; i++) {
                        def health = sh(
                            script: "docker inspect --format='{{.State.Health.Status}}' ${newSlot} 2>/dev/null || echo 'starting'",
                            returnStdout: true
                        ).trim()
                        if (health == 'healthy') {
                            echo "✅ [${newSlot}] HEALTHY (${i}/15)"
                            success = true
                            break
                        }
                        echo "⏳ [${newSlot}] ${health} (${i}/15) - 5s 대기..."
                        sleep 5
                    }
                    if (!success) {
                        sh "docker logs --tail 50 ${newSlot} || true"
                        error "❌ Health Check 실패: ${newSlot}"
                    }

                    // 4. JVM 워밍업 (첫 요청 지연 방지)
                    sh "docker exec ${newSlot} curl -s -o /dev/null http://localhost:8080/login || true"
                    echo "✅ [${newSlot}] JVM 워밍업 완료"

                    // 5. Nginx 무중단 스위칭 (upstream 없이 service-url.inc 변수 변경 후 reload)
                    sh """
                        echo "set \\\$service_url ${newSlot};" > ${DEPLOY_DIR}/nginx/service-url.inc
                        docker exec ${NGINX_CONTAINER} nginx -s reload
                    """
                    echo "✅ Nginx 트래픽 무중단 전환 완료 → ${newSlot}"

                    // 6. 트래픽이 완전히 넘어간 후 구 슬롯 안전하게 중지
                    if (currentSlot && currentSlot != newSlot) {
                        sh "docker stop ${currentSlot} || true"
                        echo "✅ 구 슬롯 [${currentSlot}] 중지 완료"
                    }
                }
            }
        }
    }

    post {
        success {
            echo "🎉 ${SERVICE_NAME} 무중단 배포 성공 | Image: ${SERVICE_NAME}:${env.DOCKER_TAG}"
        }
        failure {
            echo "❌ ${SERVICE_NAME} 배포 실패"
        }
    }
}
