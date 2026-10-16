# Generated audio fixtures

These files contain synthetic tones and/or black video frames generated locally with FFmpeg. They contain no recorded vehicle or third-party media. FFmpeg is only a fixture-generation tool; the app does not depend on it.

Regenerate from the repository root:

```sh
ffmpeg -f lavfi -i color=c=black:s=160x90:r=5:d=1 -f lavfi -i 'aevalsrc=0.15*sin(2*PI*880*t)|0.12*sin(2*PI*1320*t):s=44100:d=1' -map 0:v:0 -map 1:a:0 -c:v mpeg4 -c:a aac -profile:a aac_low -b:a 128k -t 1 -y app/src/androidTest/assets/synthetic-aac-video.mp4
ffmpeg -f lavfi -i color=c=black:s=160x90:r=5:d=1 -an -c:v mpeg4 -y app/src/androidTest/assets/synthetic-video-only.mp4
ffmpeg -f lavfi -i 'aevalsrc=0.15*sin(2*PI*880*t)|0.12*sin(2*PI*1320*t):s=44100:d=0.2' -c:a pcm_s24le -y app/src/androidTest/assets/synthetic-pcm24.wav
ffmpeg -f lavfi -i 'aevalsrc=0.15*sin(2*PI*880*t)|0.12*sin(2*PI*1320*t):s=44100:d=0.2' -c:a pcm_f32le -y app/src/androidTest/assets/synthetic-float32.wav
```

The stereo channels use different frequencies so tests detect accidental downmixing. The video fixture puts a video track before the AAC audio track. The WAV fixtures exercise externally generated format headers as well as sample conversion; pure JVM tests cover additional integer/float widths and malformed files. Existing `synthetic-tone.mp3` and `synthetic.erofs` fixtures remain unchanged.
