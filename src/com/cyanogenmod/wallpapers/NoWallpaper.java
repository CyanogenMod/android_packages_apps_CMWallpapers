/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cyanogenmod.wallpapers;

import android.app.Activity;
import android.app.WallpaperManager;
import android.os.Bundle;
import android.view.View;

public class NoWallpaper extends Activity {
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        setContentView(R.layout.wallpaper_preview);
    }

    public void setWallpaper(View v) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);
            wm.setResource(R.drawable.black);
        } catch (java.io.IOException e) {
        }
        setResult(RESULT_OK);
        finish();
    }
}
