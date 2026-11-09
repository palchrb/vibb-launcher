/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
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
 *
 * Modified for Vibb One (palchrb/vibb-launcher), 2026: from AOSP Messaging's
 * com.android.messaging.util.ContentType, only the constants the codec uses (no
 * android.webkit.MimeTypeMap). See README.md in this package.
 */

package com.kidslauncher.mdm.sms.vendor.pdu;

public final class ContentType {
    public static final String MMS_MULTIPART_MIXED   = "application/vnd.wap.multipart.mixed";
    public static final String MMS_MULTIPART_RELATED = "application/vnd.wap.multipart.related";
    public static final String MMS_MULTIPART_ALTERNATIVE =
            "application/vnd.wap.multipart.alternative";

    private ContentType() {
    }
}
