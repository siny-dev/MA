# MA (Mother’s Assistant)

새 Android 앱 프로젝트입니다. Mobile Basic Tool에서 분리 생성했으며 AutoSync/AutoUpdate 모듈 API 1 연결부를 포함합니다.

## 현재 앱 흐름

- 첫 화면은 흰 배경에 위쪽 MA·Mother's Assistant, 오른쪽 설정 아이콘, 아래쪽 큰 말하기 버튼과 작은 택시 아이콘으로 구성됩니다.
- 말하기 버튼을 누르고 있는 동안만 한국어 음성을 듣습니다. 손을 떼면 그 입력을 끝냅니다.
- 사용자의 한국어 목적지 표현을 로컬 Gemini 프록시에 보내 장소 검색 표현으로 정리합니다. AI가 주소나 좌표를 만들지 않고, 실제 주소 후보는 Android Geocoder에서 받아 확인합니다.
- AI가 동네나 장소를 구분할 수 없으면 쉬운 한국어로 되묻습니다. 주소 후보를 보여주고 사용자가 확인을 눌러야 외부 길찾기 앱을 엽니다. 대중교통은 NAVER 지도 경로 검색으로 연결합니다.
- 택시 아이콘은 택시 목적지를 듣고 확인한 뒤 Kakao T를 엽니다. 확인한 목적지는 클립보드에 복사합니다. 출발지·요금·결제 정보를 확인하고 실제 호출을 누르는 단계는 Kakao T 안에서 사용자가 직접 진행합니다.
- 후보가 없거나 모호하면 되묻고, 취소할 수 있습니다. 집 주소는 입력 없이 추측하지 않습니다.
- MA는 목적지 이력을 저장하거나 AutoSync에 보내지 않습니다. 음성 인식/목적지 표현은 Android 음성 인식과 Gemini 장소 해석, 인터넷 주소 검색에 사용될 수 있습니다.

## 모듈과 로컬 동작

- AutoSync 서버 주소와 토큰이 비어 있으면 데이터는 이 기기에만 보관되며 다른 기기로 동기화되지 않습니다. 목적지 정보는 현재 동기화 저장소에 기록하지 않습니다.
- `build-mobile.ps1 -Task all`은 테스트·debug 빌드 다음 로컬 업데이트 피드에 새 버전과 SHA-256 manifest를 게시하고, 이 PC에서 업데이트 서버를 숨겨 실행합니다. 로컬 AutoUpdate 게시만 하려면 `tools/autoupdate/publish-local-update.ps1`을 실행합니다.
- 앱은 실행 중 10분 간격, 백그라운드에서 Android JobScheduler를 통해 업데이트를 확인합니다. 새 파일은 자동으로 내려받아 서명·버전·SHA-256을 검증하고 Android 설치 화면을 시작합니다. 최초 설정 때 이 앱의 ‘알 수 없는 앱 설치’ 권한을 허용해야 하며, 일반 Android는 보안상 각 업데이트의 설치를 최종 확인해야 합니다.
- 기존 설치본에 업데이트 주소가 없으면 첫 1회만 휴대전화 브라우저에서 `http://<이 PC의 사설 IP>:18766/ma-3c44c3e1b8/install`을 열어 업데이트 지원 버전으로 교체합니다. 현재 이 PC의 주소는 `192.168.50.109`입니다. 설치 권한과 Android 설치 확인은 필요합니다.
- 첫 교체 뒤에는 패치 APK를 휴대전화로 옮기지 않습니다. 같은 Wi-Fi의 앱이 새 manifest와 버전을 자동 확인하고 내려받아 Android 설치 화면을 엽니다. PC가 꺼져 있거나 휴대전화가 다른 네트워크이면 PC가 다시 켜지고 같은 네트워크에 연결될 때까지 대기합니다.
- Gemini API 키는 Android APK에 저장하지 않습니다. Gemini API 호출은 PC에서 실행하는 loopback 프록시가 담당하고, 키는 프록시 프로세스 환경 변수에만 둡니다.
- Firebase, GitHub 저장소, 기본 Gemini API 키는 이 프로젝트에 저장하지 않았습니다.

## 주요 제약

- Gemini 목적지 해석 프록시는 `tools/gemini-proxy`에 있습니다. Google AI Studio에서 Gemini API 키를 만든 뒤 `tools/gemini-proxy/start-proxy.vbs`를 실행해 비밀번호 입력칸에 입력하세요. 키는 파일이나 APK에 저장되지 않고, 프록시는 PC의 loopback에만 열립니다. Android 기기를 USB 연결하고 `C:\AI-Work\Toolchains\MobileBasicTool\android-sdk\platform-tools\adb.exe reverse tcp:18765 tcp:18765`를 실행해야 기본 localhost 주소로 연결됩니다. 프록시를 닫을 때는 `tools/gemini-proxy/stop-proxy.vbs`를 실행합니다. Gemini API 키가 없거나 프록시에 연결할 수 없으면 앱은 위치를 임의로 검색하지 않고 연결 오류를 안내합니다.
- 프록시 검증: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gemini-proxy/test.ps1`
- 실제 주소 후보는 Android Geocoder 기반이며, Geocoder 서비스 가용성은 기기/네트워크에 따라 달라질 수 있습니다.
- NAVER 대중교통 경로 화면까지의 기기 실물 실행은 아직 확인되지 않았습니다. 경로 검색 화면 표시와 실제 길안내 시작은 구분해야 합니다.
- Kakao T의 공개된 외부 택시 호출 API/딥링크는 확인하지 못해 MA에서 호출을 자동 확정하지 않습니다. 택시 호출 기능은 Kakao T 앱으로 목적지를 넘기는 단계까지이며, 실제 호출은 Kakao T에서 확인해야 합니다.
- 테스트용 Android 기기가 연결되지 않아 실기기 음성 입력, Geocoder 결과, NAVER 인계는 시험 전입니다.

## 로컬 빌드 및 자동 업데이트 피드

프로젝트를 생성한 PC의 Mobile Basic Tool 도구 체인을 사용합니다.

- 테스트·빌드·로컬 업데이트 게시: `build-mobile.ps1 -Task all`
- debug 빌드만: `build-mobile.ps1 -Task debug`
- 로컬 업데이트 서버는 Windows 로그인 때 숨김으로 자동 시작합니다. 자동 시작 제거: `tools/autoupdate/install-local-update-startup.ps1 -Remove`
- 로컬 업데이트 서버 중지: `tools/autoupdate/stop-local-update-server.ps1`
- 로컬 feed는 `tools/autoupdate/feed/<app_id>/`에 저장되며, TCP 18766에서 이 PC의 사설 IPv4 주소에만 연결됩니다.
- 1회 설치 링크 경로: `http://<이 PC의 사설 IP>:18766/ma-3c44c3e1b8/install`
- Debug APK: app/build/outputs/apk/debug/app-debug.apk
- 앱 ID: ma-3c44c3e1b8
- Android application ID: com.siny.apps.ma_3c44c3e1b8
