-- Run after ./deploy.sh reseed, before capturing the app screens for the video.
-- In the story, Omar is the first man: three years of card debt became a bank loan.
-- The demo family's car loan (AED 2,100 a month, 18 payments left) becomes that loan.
UPDATE commitment
   SET name = 'Loan for the old card debt', balance_left_minor = 3780000
 WHERE id = '00000000-0000-0000-0000-0000000002c3';
