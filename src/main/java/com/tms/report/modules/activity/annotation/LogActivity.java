package com.tms.report.modules.activity.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method for admin activity logging. After the method
 * executes successfully, an entry is written to admin_activities.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogActivity {
    /** The action name (e.g. "credit", "addPnd"). */
    String action();

    /**
     * Description template. Supported placeholders: {admin} - admin name (subject)
     * {id} - @PathVariable value {user} - resolved user name (object) via userFrom
     * strategy {body.field} - field from @RequestBody
     */
    String description();

    /**
     * Strategy to resolve the affected user name for the {user} placeholder.
     * <ul>
     * <li>"" (default) – no user resolution</li>
     * <li>"body.email:fieldName" – look up user by email from request body
     * field</li>
     * <li>"entity:ClassName" – look up userId from the entity found
     * by @PathVariable id</li>
     * </ul>
     */
    String userFrom() default "";
}
