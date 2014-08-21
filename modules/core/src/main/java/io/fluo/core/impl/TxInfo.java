/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluo.core.impl;

import io.fluo.api.data.Bytes;
import io.fluo.api.data.Column;
import io.fluo.core.iterators.RollbackCheckIterator;

import java.util.Map.Entry;

import io.fluo.core.util.ColumnUtil;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;

public class TxInfo {
  public TxStatus status = null;
  public long commitTs = -1;
  public byte[] lockValue = null;

  /**
   * determine the what state a transaction is in by inspecting the primary column
   */
  public static TxInfo getTransactionInfo(Environment env, Bytes prow, Column pcol, long startTs) {
    // TODO ensure primary is visible

    IteratorSetting is = new IteratorSetting(10, RollbackCheckIterator.class);
    RollbackCheckIterator.setLocktime(is, startTs);

    Entry<Key,Value> entry = ColumnUtil.checkColumn(env, is, prow, pcol);

    TxInfo txInfo = new TxInfo();

    if (entry == null) {
      txInfo.status = TxStatus.UNKNOWN;
      return txInfo;
    }

    long colType = entry.getKey().getTimestamp() & ColumnUtil.PREFIX_MASK;
    long ts = entry.getKey().getTimestamp() & ColumnUtil.TIMESTAMP_MASK;

    if (colType == ColumnUtil.LOCK_PREFIX) {
      if (ts == startTs) {
        txInfo.status = TxStatus.LOCKED;
        txInfo.lockValue = entry.getValue().get();
      } else
        txInfo.status = TxStatus.UNKNOWN; // locked by another tx
    } else if (colType == ColumnUtil.DEL_LOCK_PREFIX) {
      DelLockValue dlv = new DelLockValue(entry.getValue().get());

      if (dlv.getTimestamp() != startTs) {
        // expect this to always be false, must be a bug in the iterator
        throw new IllegalStateException(prow + " " + pcol + " (" + dlv.getTimestamp() + " != " + startTs + ") ");
      }

      if (dlv.isRollback()) {
        txInfo.status = TxStatus.ROLLED_BACK;
      } else {
        txInfo.status = TxStatus.COMMITTED;
        txInfo.commitTs = ts;
      }
    } else if (colType == ColumnUtil.WRITE_PREFIX) {
      long timePtr = WriteValue.getTimestamp(entry.getValue().get());

      if (timePtr != startTs) {
        // expect this to always be false, must be a bug in the iterator
        throw new IllegalStateException(prow + " " + pcol + " (" + timePtr + " != " + startTs + ") ");
      }

      txInfo.status = TxStatus.COMMITTED;
      txInfo.commitTs = ts;
    } else {
      throw new IllegalStateException("unexpected col type returned " + colType);
    }

    return txInfo;
  }
}
