# 1. JavaCV
pros:
- decodes every codec found in an mp4, such as iphone's default recording if we do our own video
- native decoding that is fast -- wont slow down our code
- accurate seeking and frame timestamps

cons:
- large dependency, would need to be trimmed 
- bad file might crash instead of throwing exception due to native code
- might be overkill -- has openCV functions we may not be allowed to use, since we must implement centroidFinder

# 2. Jaffree
pros:
- handles any codec -- can use phone videos
- small and readable implementation
- can write annotated output

cons:
- user must install FFMpeg and have it on the path
- callback / push model is less convenient
- process piping = overhead

# 3. JCodec
pros:
- small dependency
- no install
- machine versatility -- works the same for diff systems

cons:
- only decodes H.264, doesn't include HEVC phone videos. would have to re-encode phone videos first
- noticeably slow
- no recent development on it, dormant tool