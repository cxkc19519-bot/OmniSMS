package com.omnisms.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    @Test fun onlySystemMessagesPackageIsAllowed(){
        assertTrue(NotificationPolicy.isAllowedPackage("com.android.mms"))
        assertFalse(NotificationPolicy.isAllowedPackage("com.example.bank"))
        assertFalse(NotificationPolicy.isAllowedPackage(null))
    }

    @Test fun longestNotificationBodyWins(){
        assertTrue(NotificationPolicy.preferredBody(listOf("验证码…","完整的虚构5G消息验证码为 123456"))=="完整的虚构5G消息验证码为 123456")
    }

    @Test fun aggregateCountIsRemovedOnlyFromTheBeginning(){
        assertTrue(NotificationPolicy.cleanAggregatePrefix("[2 条] 验证码：123456") == "验证码：123456")
        assertTrue(NotificationPolicy.cleanAggregatePrefix("[12条]验证码：123456") == "验证码：123456")
        assertTrue(NotificationPolicy.cleanAggregatePrefix("正文中的 [2 条] 保持不变") == "正文中的 [2 条] 保持不变")
        assertTrue(NotificationPolicy.cleanAggregatePrefix("[提示] 验证码：123456") == "[提示] 验证码：123456")
    }

    @Test fun recentStandardSmsSuppressesItsNotificationCopy(){
        assertTrue(NotificationPolicy.isRecentStandardSms(20_000L,5_000L))
        assertFalse(NotificationPolicy.isRecentStandardSms(20_001L,5_000L))
        assertFalse(NotificationPolicy.isRecentStandardSms(20_000L,0L))
        assertFalse(NotificationPolicy.isRecentStandardSms(20_000L,20_001L))
    }
}
