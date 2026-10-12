# Spotify++ FAQ

### It does not work with my Spotify version

Spotify++ compatibility depends on the Spotify version.

This fork is built and tested against Spotify **9.1.88 – 9.1.90** from Google Play. Older, beta,
ReVanced, or modified builds may not work.

### What is included?

- Translation, transliteration, romanization, dictionaries, extra fonts, and the
	renderer are included in the single APK.
- The now-playing card, settings, and HyperGlow bridge are included as well.
- Language models (Japanese/Chinese dictionaries, language detection) are downloaded
	on demand from Settings instead of being bundled.

### Which LSPosed scope?

Spotify only.

After install or update:

1. Force-stop Spotify.
2. Reopen Spotify.

### Spotify++ settings are missing

Check:

- Module enabled.
- Spotify selected in LSPosed scope.
- Spotify fully restarted.
- Spotify build compatible with current hooks.

Still broken? Open an issue in this repository with your Spotify version, the module version and
whether you use LSPosed or LSPatch.

### Does LSPatch work?

Possible, but less reliable. Patched Spotify may fail Play Integrity or login.
Follow the [downgrade-login-upgrade method](README.en.md#installation) in the Install section,
or the same steps in Chinese: [降级-登录-升级方法](readme.md#安装).

### Lyrics are missing, wrong, or delayed

Lyric availability, text, language, and timing quality can vary by track and by source.

### HyperGlow

Optional.

### How do I update?

Install the new APK over the old installation. Then restart Spotify.

If you use LSPatch, enable **Override version code**.
