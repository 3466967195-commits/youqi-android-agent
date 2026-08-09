package com.youqi.studio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class TermuxResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int requestId = intent.getIntExtra(TermuxBridge.EXTRA_REQUEST_ID, -1);
        Bundle result = intent.getBundleExtra("result");
        TermuxBridge.complete(requestId, result);
    }
}
