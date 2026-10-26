# AutoTag — 음악 파일 자동 태그 + 싱크가사 채우기 (Android, 개인용)

음악 파일(MP3/FLAC/M4A/OGG/OPUS/WAV/AIFF/DSF 등)을 골라서 **파일 자체의 태그를 고쳐 주는**
안드로이드 앱입니다. 서버 없이 폰에서 직접 동작합니다.

- AcoustID 지문(Chromaprint)으로 **곡을 정확히 식별**하고, 실패하면 제목+가수로 MusicBrainz /
  iTunes / Deezer를 조회합니다.
- **싱크가사(LRC)를 우선**으로 채웁니다. ID3(SYLT+USLT), M4A(©lyr), FLAC/OGG(LYRICS)에
  기록하고 `<곡이름>.lrc` 파일도 함께 만듭니다(옵션).
- 가수/앨범 표기를 배치 전체에서 **하나로 통일**해서, 플레이어에서 같은 가수가 글자 차이로
  다른 사람처럼 보이는 문제를 줄입니다(학습된 별칭은 다음 실행에도 재사용).
- 커버 이미지 삽입, 트랙/디스크 번호, 연도, 장르, ISRC, MusicBrainz ID 기록.
- 적용 전/후 변경 내역을 로그로 전부 보여 줍니다.

## 빌드 (APK)

Android Studio 또는 로컬 Gradle로 직접 빌드합니다 (CI 워크플로는 두지 않았습니다).

```bash
# JDK 17 + Gradle 8.7 (또는 Android Studio에서 프로젝트 열고 Run)
gradle :app:assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk  → 폰에 복사해서 설치
```

테스트 (기기 없이 JVM에서 실행):

```bash
gradle :fp:test :app:testDebugUnitTest
```

- `:fp:test` — Chromaprint(지문) 포팅을 chromaprint 1.5.1 공식 테스트 데이터와 대조
- `:app:testDebugUnitTest` — 실제 오디오 샘플(mp3/flac/ogg/wav/m4a)에 태그를 쓰고 다시 읽어 검증

## 사용법

1. 앱 실행 → **권한** 버튼 → "모든 파일 액세스 허용"(파일을 직접 수정하기 위해 필요).
2. **음악 전체**(MediaStore 스캔) / **폴더**(폴더 선택) / **파일**(SAF 선택) 중 하나로 음악을 불러옵니다.
3. 목록에서 처리할 파일을 체크(기본 전체 선택) → **태그 채우기 시작**.
4. 아래 로그에서 파일별로 무엇이 바뀌었는지 확인합니다.
5. 필요하면 **설정**에서 소스/정책을 바꿉니다.

### 설정 요약

| 항목 | 설명 |
| --- | --- |
| AcoustID 지문 사용 | Chromaprint 지문으로 정확한 녹음 식별 (기본 ON, 점수 70% 이상 채택) |
| MusicBrainz / iTunes / Deezer | 텍스트 기반 보조 조회 |
| LRCLIB 가사 | 싱크가사 우선 검색 |
| 빈 항목만 채우기 | 기존에 값이 있으면 유지 (끄면 DB 값으로 덮어씀) |
| 가수/앨범 표기 통일 | 배치 내 표기를 하나로 통일 |
| 가수에서 feat. 제거 | ARTIST는 대표 가수만, 나머지는 제목에 유지 |
| .lrc 파일 생성 | 오디오 옆에 싱크가사 파일 생성 (많은 안드로이드 플레이어가 인식) |
| 원본 .bak 백업 | 수정 전 원본을 `<파일>.bak` 으로 보관 |

## 어떤 파일을 고치나

| 형식 | 태그 | 싱크가사 | 커버 |
| --- | --- | --- | --- |
| mp3 | ID3v2.3/2.4 | SYLT + USLT | APIC |
| flac | Vorbis comment | LYRICS(+.lrc) | METADATA_BLOCK_PICTURE |
| m4a/mp4 | iTunes atoms | ©lyr(+.lrc) | covr |
| ogg/oga/opus | Vorbis comment | LYRICS(+.lrc) | METADATA_BLOCK_PICTURE |
| wav/aiff | ID3 chunk | USLT(+.lrc) | APIC |
| dsf | ID3 | USLT(+.lrc) | APIC |

> 안드로이드 플레이어 중 SYLT를 읽는 앱은 많지 않습니다. 그래서 **.lrc 파일 생성**과
> **LYRICS/USLT에 LRC 타임스탬프 텍스트 기록**을 함께 합니다(파워앰프/뮤직클렛 등은 .lrc를,
> 일부 앱은 태그 안의 LRC 텍스트를 읽습니다).

## 구조

```
app/                     안드로이드 앱 (Kotlin, AndroidX 미사용 → 의존성 최소)
  src/main/java/com/troy/autotag/
    MainActivity.kt       UI(파일 선택, 진행, 로그, 설정)
    net/Http.kt           HTTP + 호스트별 레이트리밋 + 재시도
    sources/              AcoustID, MusicBrainz, iTunes, Deezer, LRCLIB
    enrich/EnrichEngine   식별 → 표기 통일 → 파일 쓰기 (3단계)
    tag/TagIO.kt          jaudiotagger 읽기/쓰기 (SYLT, 커버 포함)
    lyrics/Lrc.kt         LRC 파싱/SYLT 페이로드 생성
    audio/AudioDecoder    MediaCodec으로 PCM 디코딩(지문용)
  src/main/java/org/jaudiotagger/   jaudiotagger 2.3.15 소스 (LGPL, 안드로이드용 패치)
  src/main/java/org/jcodec/          JCodec MP4 서브셋 (jaudiotagger MP4 기록용, BSD-2)
  src/test/kotlin/                   JVM 단위 테스트 (태그 쓰기/가사/문자열 정규화)
  src/test/resources/tag/            테스트용 오디오 샘플 (mp3/flac/ogg/wav/m4a)
fp/                      Chromaprint(AcoustID 지문) 순수 Kotlin 포팅 + 참조 테스트
```

## 라이선스 / 크레딧

- jaudiotagger (LGPL 2.1) 소스를 포함합니다: `app/third_party/jaudiotagger/LICENSE.txt`.
  Android에서 컴파일되도록 AWT/ImageIO 의존 클래스만 제거/수정했습니다.
- Chromaprint 포팅은 MIT 라이선스 원본 알고리즘을 따릅니다:
  `fp/third_party_chromaprint_LICENSE.md`. 참조 테스트 데이터는 chromaprint 1.5.1의 것입니다.
- 메타데이터: MusicBrainz, AcoustID, iTunes Search API, Deezer API, LRCLIB.
