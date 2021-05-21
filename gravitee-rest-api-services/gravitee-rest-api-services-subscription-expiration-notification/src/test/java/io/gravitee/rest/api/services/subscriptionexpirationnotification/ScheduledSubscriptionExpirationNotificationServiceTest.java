/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.rest.api.services.subscriptionexpirationnotification;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import io.gravitee.rest.api.model.*;
import io.gravitee.rest.api.model.permissions.RoleScope;
import io.gravitee.rest.api.model.permissions.SystemRole;
import io.gravitee.rest.api.model.subscription.SubscriptionQuery;
import io.gravitee.rest.api.service.*;
import io.gravitee.rest.api.service.builder.EmailNotificationBuilder;
import io.gravitee.rest.api.service.common.GraviteeContext;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ScheduledSubscriptionExpirationNotificationServiceTest {

    @InjectMocks
    ScheduledSubscriptionExpirationNotificationService service = new ScheduledSubscriptionExpirationNotificationService();

    @Mock
    UserService userService;

    @Mock
    RoleService roleService;

    @Mock
    MembershipService membershipService;

    @Mock
    SubscriptionService subscriptionService;

    @Mock
    EmailService emailService;

    @Test
    public void shouldCleanNotificationDays() {
        List<Integer> inputNotificationDays = Arrays.asList(-1, 150, 75, 10, 30, 400, 45);
        List<Integer> cleanedNotificationDays = service.getCleanedNotificationDays(inputNotificationDays);

        assertEquals(Arrays.asList(150, 75, 45, 30, 10), cleanedNotificationDays);
    }

    @Test
    public void shouldFindSubscriptionToNotify() {
        Date now = new Date(1469022010000L);
        long daysBeforeNotification = 10;

        SubscriptionEntity subscription = mock(SubscriptionEntity.class);

        when(subscriptionService.search(any(SubscriptionQuery.class))).thenReturn(Collections.singletonList(subscription));

        Collection<SubscriptionEntity> subscriptionsToNotify = service.findSubscriptionToNotify(now, daysBeforeNotification);

        assertEquals(Collections.singletonList(subscription), subscriptionsToNotify);

        verify(subscriptionService, times(1))
            .search(
                argThat(
                    subscriptionQuery ->
                        Arrays.asList(SubscriptionStatus.ACCEPTED, SubscriptionStatus.PAUSED).equals(subscriptionQuery.getStatuses()) &&
                        // 1469886010000 -> now + 10 days
                        subscriptionQuery.getEndingAtAfter() ==
                        1469886010000L &&
                        // 1469889610000 -> now + 10 days + 1h (cron period)
                        subscriptionQuery.getEndingAtBefore() ==
                        1469889610000L
                )
            );
    }

    @Test
    public void shouldFindEmailToNotifyWithDifferentSubscriberAndPrimaryOwner() {
        String subscriberId = UUID.randomUUID().toString();
        UserEntity subscriber = mock(UserEntity.class);
        when(subscriber.getEmail()).thenReturn("subscriber@gravitee.io");

        SubscriptionEntity subscription = mock(SubscriptionEntity.class);
        when(subscription.getSubscribedBy()).thenReturn(subscriberId);
        when(subscription.getApplication()).thenReturn(UUID.randomUUID().toString());

        when(userService.findById(subscriberId)).thenReturn(subscriber);

        RoleEntity role = mock(RoleEntity.class);
        when(role.getId()).thenReturn(UUID.randomUUID().toString());
        when(roleService.findByScopeAndName(RoleScope.APPLICATION, SystemRole.PRIMARY_OWNER.name())).thenReturn(Optional.of(role));

        MemberEntity memberEntity = mock(MemberEntity.class);
        when(memberEntity.getEmail()).thenReturn("primary_owner@gravitee.io");

        when(
            membershipService.getMembersByReferencesAndRole(
                MembershipReferenceType.APPLICATION,
                Collections.singletonList(subscription.getApplication()),
                role.getId()
            )
        )
            .thenReturn(new HashSet<>(Collections.singletonList(memberEntity)));

        Collection<String> usersToNotify = service.findEmailsToNotify(subscription);

        Set<String> expected = new HashSet<>();
        expected.add("subscriber@gravitee.io");
        expected.add("primary_owner@gravitee.io");
        assertEquals(expected, usersToNotify);
    }

    @Test
    public void shouldFindEmailToNotifyWithSameSubscriberAndPrimaryOwner() {
        String subscriberId = UUID.randomUUID().toString();
        UserEntity subscriber = mock(UserEntity.class);
        when(subscriber.getEmail()).thenReturn("primary_owner@gravitee.io");

        SubscriptionEntity subscription = mock(SubscriptionEntity.class);
        when(subscription.getSubscribedBy()).thenReturn(subscriberId);
        when(subscription.getApplication()).thenReturn(UUID.randomUUID().toString());

        when(userService.findById(subscriberId)).thenReturn(subscriber);

        RoleEntity role = mock(RoleEntity.class);
        when(role.getId()).thenReturn(UUID.randomUUID().toString());
        when(roleService.findByScopeAndName(RoleScope.APPLICATION, SystemRole.PRIMARY_OWNER.name())).thenReturn(Optional.of(role));

        MemberEntity memberEntity = mock(MemberEntity.class);
        when(memberEntity.getEmail()).thenReturn("primary_owner@gravitee.io");

        when(
            membershipService.getMembersByReferencesAndRole(
                MembershipReferenceType.APPLICATION,
                Collections.singletonList(subscription.getApplication()),
                role.getId()
            )
        )
            .thenReturn(new HashSet<>(Collections.singletonList(memberEntity)));

        Collection<String> usersToNotify = service.findEmailsToNotify(subscription);

        Set<String> expected = new HashSet<>();
        expected.add("primary_owner@gravitee.io");
        assertEquals(expected, usersToNotify);
    }

    @Test
    public void shouldSendEmail() {
        SubscriptionEntity subscription = mock(SubscriptionEntity.class);
        String subscriberEmail = "subscriber@gravitee.io";
        String environmentId = UUID.randomUUID().toString();

        service.sendEmail(subscriberEmail, subscription, environmentId);


        EmailNotification emailNotification = new EmailNotificationBuilder()
            .to("subscriber@gravitee.io")
            .template(EmailNotificationBuilder.EmailTemplate.TEMPLATES_FOR_ACTION_GENERIC_MESSAGE)
            .build();

        verify(emailService, times(1))
            .sendAsyncEmailNotification(eq(emailNotification), any(GraviteeContext.ReferenceContext.class));
    }
}
