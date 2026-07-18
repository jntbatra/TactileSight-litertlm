// Jenkins pipeline for TactileSight.
//
// The Android app is being rebuilt (see CONTEXT.md, "REBUILD"), so `android/` does not exist yet.
// Every Android stage is therefore gated on the module actually being present: the pipeline stays
// green today on `server/` alone, and starts building + testing the app automatically the moment
// issue #1 lands the walking skeleton. No edit to this file is needed when that happens.
pipeline {

    agent any

    stages {
        stage('Server tests') {
            when { expression { fileExists('server/test_app.py') } }
            steps {
                dir('server') {
                    // TS_VLM_BACKEND=mock keeps this hermetic — no model, no GPU, no network.
                    // Green here IS the compatibility check between the app and server tracks.
                    sh '''
                        python3 -m venv .venv
                        . .venv/bin/activate
                        pip install --quiet -r requirements.txt
                        TS_VLM_BACKEND=mock python -m pytest -q --junitxml=test-results.xml
                    '''
                }
            }
        }

        stage('Android unit tests') {
            when { expression { fileExists('android/gradlew') } }
            steps {
                dir('android') {
                    // JDK 21 (Android Studio's JBR). JDK 25 breaks Gradle's Kotlin compiler with
                    // `IllegalArgumentException: 25.0.3` — see TEAM.md.
                    sh './gradlew testDebugUnitTest --no-daemon --console=plain'
                }
            }
        }

        stage('Build APK') {
            when { expression { fileExists('android/gradlew') } }
            steps {
                dir('android') {
                    sh './gradlew assembleDebug --no-daemon --console=plain'
                }
            }
        }
    }

    post {
        always {
            junit testResults: 'server/test-results.xml', allowEmptyResults: true
            junit testResults: 'android/app/build/test-results/testDebugUnitTest/*.xml',
                  allowEmptyResults: true
        }
        success {
            archiveArtifacts artifacts: 'android/app/build/outputs/apk/debug/*.apk',
                             fingerprint: true,
                             allowEmptyArchive: true
        }
    }
}
