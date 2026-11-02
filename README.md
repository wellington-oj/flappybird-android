# Flappy Bird (Android · Kotlin)

Clone simples de Flappy Bird em Kotlin puro, desenhado com `Canvas` num `SurfaceView` — sem imagens nem bibliotecas externas.

## Como jogar
- Toque na tela para bater as asas.
- Passe pelos canos para marcar pontos; o recorde fica salvo no aparelho.

## Rodar
1. Abra a pasta no **Android Studio** (Ladybug ou mais recente).
2. Aguarde o Gradle sincronizar.
3. Ligue o telemóvel com *Depuração USB* ativa (ou use um emulador) e clique em **Run ▶**.

Linha de comando:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Estrutura
| Arquivo | Função |
|---|---|
| `MainActivity.kt` | Tela cheia imersiva, pausa/retoma o jogo |
| `GameView.kt` | Loop do jogo, física, colisões, pontuação e desenho |

## Ajustes rápidos
Constantes no topo de `GameView.kt`: `GRAVITY`, `FLAP_VELOCITY`, `SPEED`, `PIPE_GAP`, `PIPE_INTERVAL`.

Requisitos: Android 7.0+ (API 24), AGP 8.7, Kotlin 2.0, JDK 17.
