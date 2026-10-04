package com.spotifyplusplus.hooks;

import com.spotifyplusplus.xposed.XpPackage;
import com.spotifyplusplus.xposed.SpotifySymbolResolver;

public abstract class SpotifyHook {
    protected XpPackage lpparm;
    protected SpotifySymbolResolver symbols;

    public void init(XpPackage lpparm, SpotifySymbolResolver symbols) {
        this.lpparm = lpparm;
        this.symbols = symbols;
        hook();
    }

    protected abstract void hook();
}
