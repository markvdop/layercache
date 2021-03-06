/*
 * Copyright 2017 Appmattus Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.appmattus.layercache.encryption

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

@Suppress("ExceptionRaisedInUnexpectedLocation")
@RequiresApi(Build.VERSION_CODES.KITKAT)
internal class AesGcmNoPadding(context: Context, keystoreAlias: String) :
        EncryptionDefaultIv(context, keystoreAlias) {
    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            throw IllegalStateException("GCM requires API 19 or higher")
        }
    }

    override val integrityCheck = IntegrityCheck.NONE

    override val blockMode = BlockMode.GCM

    override val encryptionPadding = EncryptionPadding.NONE

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    override fun generateSpec(injectionVector: ByteArray): AlgorithmParameterSpec {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("MagicNumber")
            GCMParameterSpec(128, injectionVector)
        } else {
            IvParameterSpec(injectionVector)
        }
    }
}
