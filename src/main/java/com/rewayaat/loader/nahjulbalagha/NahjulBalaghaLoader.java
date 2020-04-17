package com.rewayaat.loader.nahjulbalagha;

public class NahjulBalaghaLoader {

    public static void main(String[] args) throws Exception {
        Thread t = new NahjulBalaghWorker();
        t.start();
        t.join();
    }
}
