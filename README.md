# MA

MA is an Android voice assistant for finding a requested place and handing the confirmed destination to NAVER Maps for public-transit directions. The current local development candidate is 1.0.031 (versionCode 1016); app ID, package name, and signing identity remain unchanged for compatible updates.

## Current destination flow

- Hold **말하기** to record; releasing it stops microphone capture immediately.
- NAVER CLOVA Speech Recognition transcribes the clip, HyperCLOVA X interprets the request, and NAVER API HUB local search supplies real place names, addresses, and coordinates.
- Natural follow-ups such as adding a district, specialty, or “specialist” requirement keep the active request context and trigger a new NAVER search.
- MA asks one place-confirmation question. A rejection clears that candidate; it does not repeat the same question.
- After an affirmative answer, MA opens the NAVER Maps public-transit route using the selected NAVER coordinates.
- The small taxi button opens Kakao T. Automated taxi request confirmation is not part of this transit-first candidate.

The three API credentials are entered once in MA Settings and encrypted using the device's Android Keystore. They are not included in APK assets.

## 새 앱 저장소 생성

`새 프로그램 만들기.cmd`에서 프로그램 이름과 모바일을 선택하면 앱별 소스 저장소가 만들어집니다. AutoUpdate 피드와 Android 후보 릴리스는 공용 `siny-dev/auto-update` 저장소의 `feeds/<app_id>/stable.json` 및 릴리스 자산으로 관리됩니다. 앱 안에는 이 앱 전용 피드 URL이 생성됩니다.

명령으로 실행하려면:
py -3 "C:\AI-Work\제작중인 프로그램\DesktopBasicTool\new_basic_program.py" --name "Alarm" --platform mobile

저장소 없이 시험할 때는 --local-only를 붙입니다. 기존 폴더나 GitHub 저장소를 덮어쓰지 않습니다. 한국어 이름은 AutoUpdate 규칙에 맞는 고유한 ASCII 저장소 이름으로 변환됩니다.

## 현재 연결 범위

- 생성한 앱은 고유 app ID, Android 패키지 ID, AutoUpdate 저장소의 앱별 피드 주소를 사용합니다. 첫 피드는 설치 버전 1.0.0을 가리킵니다.
- `Release-Mobile.cmd candidate 1.0.1 2`는 이 PC에서 서명 키를 처음 한 번 만들고 Windows DPAPI로 비밀번호를 보호한 뒤, 검증된 APK를 공용 AutoUpdate 저장소의 후보 릴리스에 올립니다. 키 파일은 앱 저장소 밖의 `%LOCALAPPDATA%\Siny\AndroidSigning\<app_id>`에 보관하며 저장소에 넣지 않습니다.
- 후보 버전을 실제 기기에서 확인한 뒤 `Release-Mobile.cmd promote candidate-1.0.1-2`를 실행하면 SHA-256을 다시 확인하고 공용 AutoUpdate 저장소의 `feeds/<app_id>/stable.json`만 갱신합니다. 설치된 앱은 실행/주기 확인 때 안정 채널을 확인하고, Android 설치 승인 화면을 통해 업데이트합니다.
- 이전 코드로 되돌려 배포할 때는 `candidate`의 네 번째 인수에 기존 Git commit/tag를 지정하고, 현재 안정 버전보다 높은 versionCode를 사용합니다. Android는 더 낮은 versionCode의 앱을 업데이트로 설치하지 않으므로, 이전 코드도 새 versionCode로 다시 빌드해야 합니다.
- 간편 롤백 후보는 `Release-Mobile.cmd rollback <이전-commit-or-tag>`로 만듭니다. 안정 피드보다 높은 versionCode로 이전 소스를 다시 빌드해 후보 릴리스로만 올립니다. 후보를 확인한 뒤 `promote` 해야 안정 업데이트가 배포되며, `rollback` 자체는 안정 피드를 바꾸지 않습니다.
- 앱 화면과 업데이트 상태에 설치된 버전명·versionCode를 표시하며, 다운로드 SHA-256, APK 패키지, 버전 코드, 서명 인증서 오류를 각각 구분해 안내합니다. GitHub 릴리스 파일의 HTTPS 리디렉션은 제한된 횟수로 따라가되 HTTPS에서 HTTP로 내려가는 주소는 거부합니다.
- 서명 키를 잃으면 이미 설치된 앱과 업데이트 서명이 달라져 패치가 불가능합니다. 이 PC와 사용자 계정의 보안 백업을 유지해야 합니다.
- AutoSync 클라이언트와 로컬 대기열은 포함하지만 서버 주소와 앱 토큰은 비어 있습니다. 동기화 서버의 배포와 등록이 별도로 필요합니다.
- Firebase와 사용자 데이터는 기본툴 저장소에 포함하지 않습니다.

## 빌드

build-mobile.cmd all

## Android 버전 배포

후보 APK를 공용 AutoUpdate GitHub 저장소의 prerelease로 올립니다. 버전 코드는 안정 피드보다 반드시 커야 합니다.

Release-Mobile.cmd candidate 1.0.1 2

후보 APK 검증 후 안정 피드를 승격합니다.

Release-Mobile.cmd promote candidate-1.0.1-2
