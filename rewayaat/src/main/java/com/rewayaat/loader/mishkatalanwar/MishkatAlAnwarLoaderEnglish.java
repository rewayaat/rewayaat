package com.rewayaat.loader.mishkatalanwar;

public class MishkatAlAnwarLoaderEnglish {

    public static void main(String[] args) throws Exception {

        MishkatAlAnwarWorker t11 = new MishkatAlAnwarWorker(32, 170);
        t11.start();

        MishkatAlAnwarWorker t12 = new MishkatAlAnwarWorker(171, 306);
        t12.start();

        MishkatAlAnwarWorker t21 = new MishkatAlAnwarWorker(307, 522);
        t21.start();

        MishkatAlAnwarWorker t221 = new MishkatAlAnwarWorker(523, 623);
        t221.start();

        MishkatAlAnwarWorker t31 = new MishkatAlAnwarWorker(624, 681);
        t31.start();

        MishkatAlAnwarWorker t41 = new MishkatAlAnwarWorker(682, 764);
        t41.start();

        MishkatAlAnwarWorker t51 = new MishkatAlAnwarWorker(765, 843);

        t51.start();
        MishkatAlAnwarWorker t61 = new MishkatAlAnwarWorker(844, 927);
        t61.start();

    }

}