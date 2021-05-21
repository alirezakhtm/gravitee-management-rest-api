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

import io.gravitee.common.service.AbstractService;
import io.gravitee.rest.api.model.*;
import io.gravitee.rest.api.model.permissions.RoleScope;
import io.gravitee.rest.api.model.permissions.SystemRole;
import io.gravitee.rest.api.model.subscription.SubscriptionQuery;
import io.gravitee.rest.api.service.*;
import io.gravitee.rest.api.service.builder.EmailNotificationBuilder;
import io.gravitee.rest.api.service.common.GraviteeContext;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

public class ScheduledSubscriptionExpirationNotificationService extends AbstractService implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(ScheduledSubscriptionExpirationNotificationService.class);

    @Autowired
    private TaskScheduler scheduler;

    @Value("${* * */1 * * *}")
    private String cronTrigger;

    private int cronPeriodInMs = 60 * 60 * 1000;

    @Value("#{'${services.subscription.notificationDays:90,45,30}'.split(',')}")
    private List<Integer> configNotificationDays;

    private List<Integer> notificationDays;

    private final AtomicLong counter = new AtomicLong(0);

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private UserService userService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private EmailService emailService;

    @Override
    protected String name() {
        return "Subscription Expiration Notification service";
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        notificationDays = getCleanedNotificationDays(configNotificationDays);

        logger.info("Subscription Expiration Notification service has been initialized with cron [{}]", cronTrigger);
        scheduler.schedule(this, new CronTrigger(cronTrigger));
    }

    @Override
    public void run() {
        logger.debug("Subscription Expiration Notification #{} started at {}", counter.incrementAndGet(), Instant.now().toString());

        Date now = new Date();

        notificationDays
            .stream()
            .flatMap(day -> findSubscriptionToNotify(now, day).stream())
            .forEach(
                subscriptionEntity -> {
                    findEmailsToNotify(subscriptionEntity)
                        .forEach(
                            email -> {
                                logger.info(email);
                            }
                        );
                }
            );

        logger.debug("Subscription Expiration Notification #{} ended at {}", counter.get(), Instant.now().toString());
    }

    protected List<Integer> getCleanedNotificationDays(List<Integer> inputDays) {
        return inputDays
            .stream()
            .filter(day -> day >= 1)
            .filter(day -> day <= 366)
            .sorted(Comparator.reverseOrder())
            .collect(Collectors.toList());
    }

    protected Collection<SubscriptionEntity> findSubscriptionToNotify(Date now, long daysBeforeNotification) {
        long daysBeforeNotificationInMs = daysBeforeNotification * 24 * 60 * 60 * 1000;

        SubscriptionQuery query = new SubscriptionQuery();
        query.setStatuses(Arrays.asList(SubscriptionStatus.ACCEPTED, SubscriptionStatus.PAUSED));
        query.setEndingAtAfter(now.getTime() + daysBeforeNotificationInMs);
        query.setEndingAtBefore(now.getTime() + daysBeforeNotificationInMs + cronPeriodInMs);

        return subscriptionService.search(query);
    }

    protected Collection<String> findEmailsToNotify(SubscriptionEntity subscription) {
        HashSet<String> emails = new HashSet<>();

        String subscriberEmail = userService.findById(subscription.getSubscribedBy()).getEmail();
        emails.add(subscriberEmail);

        Set<String> primaryOwnerEmails = roleService
            .findByScopeAndName(RoleScope.APPLICATION, SystemRole.PRIMARY_OWNER.name())
            .map(
                role ->
                    membershipService.getMembersByReferencesAndRole(
                        MembershipReferenceType.APPLICATION,
                        Collections.singletonList(subscription.getApplication()),
                        role.getId()
                    )
            )
            .map(members -> members.stream().map(MemberEntity::getEmail).collect(Collectors.toSet()))
            .orElseGet(HashSet::new);
        emails.addAll(primaryOwnerEmails);

        return emails;
    }

    public void sendEmail(String subscriberEmail, SubscriptionEntity subscription, String environmentId) {
        GraviteeContext.ReferenceContext context = new GraviteeContext.ReferenceContext(
            environmentId,
            GraviteeContext.ReferenceContextType.ENVIRONMENT
        );

        Map<String, Object> emailParams = new HashMap<>();

        EmailNotification emailNotification = new EmailNotificationBuilder()
            .to(subscriberEmail)
            .template(EmailNotificationBuilder.EmailTemplate.TEMPLATES_FOR_ACTION_GENERIC_MESSAGE)
            .params(emailParams)
            .build();

        emailService.sendAsyncEmailNotification(emailNotification, context);
    }
}
