package peergos.server.tests;

import org.junit.*;
import peergos.server.routing.*;
import peergos.shared.routing.*;

import java.util.*;

public class CuckooCycleTests {

    private static CuckooCycle.Proof solve(CuckooCycle params, byte[] subject) {
        return new CuckooSolver(params).solve(subject, 0, 100_000).get();
    }

    @Test
    public void solvedProofsVerify() {
        CuckooCycle params = new CuckooCycle(16, 0);
        for (int s = 0; s < 5; s++) {
            byte[] subject = ("subject " + s).getBytes();
            CuckooCycle.Proof proof = solve(params, subject);
            Assert.assertTrue(params.check(subject, proof));
            Assert.assertTrue(params.verify(subject, proof.serialize()).join());
            CuckooCycle.Proof parsed = CuckooCycle.Proof.fromCbor(peergos.shared.cbor.CborObject.fromByteArray(proof.serialize()));
            Assert.assertArrayEquals(proof.edges, parsed.edges);
            Assert.assertEquals(proof.nonce, parsed.nonce);
        }
    }

    @Test
    public void invalidProofsFail() {
        CuckooCycle params = new CuckooCycle(16, 0);
        byte[] subject = "subject".getBytes();
        CuckooCycle.Proof proof = solve(params, subject);
        Assert.assertFalse("another subject", params.check("other".getBytes(), proof));
        Assert.assertFalse("another nonce", params.check(subject, new CuckooCycle.Proof(16, proof.nonce + 1, proof.edges)));
        Assert.assertFalse("another graph size", new CuckooCycle(17, 0).check(subject, new CuckooCycle.Proof(17, proof.nonce, proof.edges)));
        Assert.assertFalse("proof for a smaller graph", new CuckooCycle(17, 0).check(subject, proof));
        for (int i = 0; i < CuckooCycle.PROOF_SIZE; i++) {
            int[] edges = proof.edges.clone();
            edges[i] ^= 1;
            Arrays.sort(edges);
            Assert.assertFalse("edge " + i + " changed", params.check(subject, new CuckooCycle.Proof(16, proof.nonce, edges)));
        }
        int[] reversed = proof.edges.clone();
        for (int i = 0; i < reversed.length / 2; i++) {
            int t = reversed[i];
            reversed[i] = reversed[reversed.length - 1 - i];
            reversed[reversed.length - 1 - i] = t;
        }
        Assert.assertFalse("unsorted", params.check(subject, new CuckooCycle.Proof(16, proof.nonce, reversed)));
        Assert.assertFalse("garbage", params.verify(subject, new byte[]{1, 2, 3}).join());
    }

    @Test
    public void difficultyMultipliesTheWork() {
        CuckooCycle easy = new CuckooCycle(14, 0), hard = new CuckooCycle(14, 6);
        long easyNonces = 0, hardNonces = 0;
        for (int s = 0; s < 8; s++) {
            byte[] subject = ("subject " + s).getBytes();
            easyNonces += solve(easy, subject).nonce + 1;
            CuckooCycle.Proof p = solve(hard, subject);
            hardNonces += p.nonce + 1;
            Assert.assertTrue(hard.check(subject, p));
        }
        System.out.println("nonces per proof: difficulty 0 " + easyNonces / 8.0 + ", difficulty 6 " + hardNonces / 8.0);
        Assert.assertTrue(hardNonces > 4 * easyNonces);
    }

    @Test
    public void parallelSolving() {
        CuckooCycle params = new CuckooCycle(16, 2);
        byte[] subject = "parallel".getBytes();
        CuckooCycle.Proof proof = new CuckooSolver(params).solveParallel(subject, 4, 100_000).get();
        Assert.assertTrue(params.check(subject, proof));
    }

    @Test
    public void verifyIsCheap() {
        CuckooCycle params = new CuckooCycle(16, 0);
        byte[] subject = "subject".getBytes();
        CuckooCycle.Proof proof = solve(params, subject);
        byte[] raw = proof.serialize();
        for (int i = 0; i < 10_000; i++)
            params.verify(subject, raw).join();
        int n = 100_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++)
            params.verify(subject, raw).join();
        long perVerify = (System.nanoTime() - t0) / n;
        System.out.println("verify: " + perVerify + " ns, proof " + raw.length + " bytes");
        Assert.assertTrue(perVerify < 1_000_000);
    }
}
