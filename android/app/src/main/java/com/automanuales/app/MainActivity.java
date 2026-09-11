package com.automanuales.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {

        // El plugin personalizado debe registrarse ANTES
        // de inicializar el Bridge de Capacitor.
        registerPlugin(StartMyCarPlugin.class);
        registerPlugin(AutoManualesDevicePlugin.class);

        super.onCreate(savedInstanceState);
    }
}