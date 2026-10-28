package com.cnnceart.flymeears.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.cnnceart.flymeears.EncoIntents;
import com.cnnceart.flymeears.bt.EncoSnapshot;

/**
 * 「降噪」快捷磁贴：Flyme 控制中心里的原生入口。
 *
 * <p>点击只发一条广播，真正的 RFCOMM 与状态都在 SystemUI 的会话里，
 * 保证同一条链路只有一个持有者；会话把模式变化广播回来更新磁贴。
 */
public final class AncTileService extends TileService {

    private final BroadcastReceiver modeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int mode = intent.getIntExtra(EncoIntents.EXTRA_MODE, 0);
            int battery = intent.getIntExtra(EncoIntents.EXTRA_BATTERY, -1);
            applyMode(mode, battery);
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(modeReceiver, new IntentFilter(EncoIntents.ACTION_MODE),
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(modeReceiver, new IntentFilter(EncoIntents.ACTION_MODE));
        }
        applyMode(0, -1);
    }

    @Override
    public void onStopListening() {
        try {
            unregisterReceiver(modeReceiver);
        } catch (Throwable ignored) {
            // 未注册时忽略。
        }
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        sendBroadcast(new Intent(EncoIntents.ACTION_CYCLE).setPackage("com.android.systemui"));
    }

    private void applyMode(int mode, int battery) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        if (mode == 0) {
            tile.setLabel("降噪");
            tile.setState(Tile.STATE_INACTIVE);
        } else {
            String label = EncoSnapshot.modeName(mode);
            if (battery >= 0) {
                label = label + " " + battery + "%";
            }
            tile.setLabel(label);
            tile.setState(mode == EncoSnapshot.MODE_ANC ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        }
        if (tile.getIcon() == null) {
            tile.setIcon(Icon.createWithResource(this, android.R.drawable.ic_lock_silent_mode));
        }
        tile.updateTile();
    }
}
