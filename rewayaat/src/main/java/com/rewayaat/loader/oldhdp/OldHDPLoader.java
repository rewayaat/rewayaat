package com.rewayaat.loader.oldhdp;

public class OldHDPLoader {

    public static void main(String[] args) throws Exception {
        Thread t = new OldHDPWorker(6000, 6010);
        t.start();
    }
}
