# Design patterns for the Dweb

User testing 2019-06-29

## Who was there?

* Ramine Daribiha (shokunin)
* Victor Rortvedt
* Gorka Ludlow (aquigorka)
* Makoto
* Akshay (aksanoble)

## Impressions

1. It's a thing for uploading files
2. It's to upload any file.
3. Didn't look a photo gallery (not file specific)
4. Not for music
5. For very private but somehow also public
6. For small content (because of small allowance)
7. Kind of like Pastebin
8. It's for data that you care about, 
9. No other service needed.
10. Encrypted, difficult to hack.
11. Dropbox equivalent.
12. Is it for sharing or is it for backup?

## How should it work (jobs to be done)?

Gorka

1. Should be as seamless as going into any browser and dragging and dropping between file browser.
2. Context menu -- when you right-click 
3. Open with native application when opening in Peergos.
4. Moving large files should show a progress bar.
5. Only people who you've shared the folder with could read/write to the folder.
6. Dropbox without Dropbox.

Akshay

1. Immediately share content with someone over a LAN (dropbox for LAN)
2. Find a name for someone nearby, click send, they go to a page and can then download it immediately (encrypted on the way).
3. Mesh network solution

Victor

1. Sharing securely - it first tells me that I am in a safe environment. Need a visualization of "safety". Files are transformed and given a logo. 
2. When it is sent, something that shows it was shared securely. 
3. Visual optionality of being able to retract.
4. Has the recipient received it?
5. OK when uploading should be 'Dismiss'

Makoto

1. Some visual handshake to verify that the person you are sharing with is who you think they are. 
2. Some verification task?
3. Would probably end up verifying over some channel before sharing.
4. Need a shiboleth
5. Finds keybase super-annoying (can copy text but can't take a screen shot)

Ramine

1. Visualizations of encryption, manifest of what has been shared.
2. Key folder.

## Solutions

### Triage

Four options:

1. Drag and drop (8)
2. Mesh network sharing (3)
3. Everything is visually super-encrypted. (7)
4. Sharing and interactively verification (people would pay money for this). (9)
-- Diffie Hellman authentication?
-- Swap public keys over email, chat
-- Add and prove.

-- Generate a 4 digit PIN and send it to that person over another channel. Verify that your follower has received it before your confirm your would like o

-- Send a link for the person to accept the follow request.

What is the cost of delay?
