package peergos.server.tests;

import org.junit.*;
import peergos.server.corenode.UsernameValidator;

import java.util.*;

public class CorenodeTests {

    @Test
    public void isValidUsernameTest() {
        List<String> areValid = Arrays.asList(
                "chris",
                "super-califragilistic-ex",
                "z",
                "ch-ris",
                "123456789012345678901234567890ab",
                "1337",
                "alpha-beta",
                "the-god-father");

        List<String> areNotValid = Arrays.asList(
                "123456789012345678901234567890abc",
                "",
                " ",
                "super_califragilistic_expialidocious",
                "\n",
                "\r",
                "\tted",
                "-ted",
                "_ted",
                "t__ed",
                "ted_",
                " ted",
                "<ted>",
                "ted-",
                "a-_b",
                "a_-b",
                "a--b",
                "a_b",
                "fred--flinstone",
                "peter-_pan",
                "_hello",
                "hello.",
                "\b0");

        areValid.forEach(username -> Assert.assertTrue(username + " is valid", UsernameValidator.isValidUsername(username)));
        areNotValid.forEach(username -> Assert.assertFalse(username +" is not valid", UsernameValidator.isValidUsername(username)));
    }
}
