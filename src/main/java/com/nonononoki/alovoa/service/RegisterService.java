package com.nonononoki.alovoa.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.nonononoki.alovoa.Tools;
import com.nonononoki.alovoa.entity.User;
import com.nonononoki.alovoa.entity.user.Conversation;
import com.nonononoki.alovoa.entity.user.Message;
import com.nonononoki.alovoa.entity.user.UserBlock;
import com.nonononoki.alovoa.entity.user.UserDates;
import com.nonononoki.alovoa.entity.user.UserDonation;
import com.nonononoki.alovoa.entity.user.UserHide;
import com.nonononoki.alovoa.entity.user.UserImage;
import com.nonononoki.alovoa.entity.user.UserInterest;
import com.nonononoki.alovoa.entity.user.UserLike;
import com.nonononoki.alovoa.entity.user.UserNotification;
import com.nonononoki.alovoa.entity.user.UserRegisterToken;
import com.nonononoki.alovoa.entity.user.UserReport;
import com.nonononoki.alovoa.entity.user.UserWebPush;
import com.nonononoki.alovoa.model.BaseRegisterDto;
import com.nonononoki.alovoa.model.RegisterDto;
import com.nonononoki.alovoa.repo.GenderRepository;
import com.nonononoki.alovoa.repo.UserIntentionRepository;
import com.nonononoki.alovoa.repo.UserRegisterTokenRepository;
import com.nonononoki.alovoa.repo.UserRepository;

@Service
public class RegisterService {

	private final String TEMP_EMAIL_FILE_NAME = "temp-mail.txt";

	@Value("${app.token.length}")
	private int tokenLength;

	@Value("${app.age.min}")
	private int minAge;

	@Value("${app.age.max}")
	private int maxAge;

	@Value("${app.age.range}")
	private int ageRange;

	@Value("${spring.profiles.active}")
	private String profile;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private MailService mailService;

	@Autowired
	private PublicService publicService;

	@Autowired
	private UserRepository userRepo;

	// @Autowired
	// private UserDatesRepository userDatesRepo;

	@Autowired
	private GenderRepository genderRepo;

	@Autowired
	private UserIntentionRepository userIntentionRepo;

	@Autowired
	private UserRegisterTokenRepository registerTokenRepo;

	@Autowired
	private AuthService authService;

	@Autowired
	protected CaptchaService captchaService;

	@Autowired
	private UserService userService;
	
	private static final String GMAIL_EMAIL = "@gmail";

	// @Autowired
	// private TextEncryptorConverter textEncryptor;

	// @Autowired
	// private HttpServletRequest request;

	public String register(RegisterDto dto) throws Exception {

		boolean isValid = captchaService.isValid(dto.getCaptchaId(), dto.getCaptchaText());
		if (!isValid) {
			throw new Exception(publicService.text("backend.error.captcha.invalid"));
		}
		
		if(!isValidEmailAddress(dto.getEmail())) {
			throw new Exception(publicService.text("backend.error.captcha.invalid"));
		}

		User user = userRepo.findByEmail(dto.getEmail().toLowerCase());
		if (user != null) {
			throw new Exception("email_invalid");
		}
		
		if(dto.getEmail().contains(GMAIL_EMAIL)) {
			String[] parts = dto.getEmail().split("@");
			String cleanEmail = parts[0].replace(".", "") + "@" + parts[1];
			dto.setEmail(cleanEmail);
		} 
		if(dto.getEmail().contains("+")) {
			dto.setEmail(dto.getEmail().split("+")[0] + "@" + dto.getEmail().split("@")[1]);
		}

		// check if email is in spam mail list
		if (profile.equals(Tools.PROD)) {
			try {
				//check spam domains
				if (Tools.isTextContainingLineFromFile(TEMP_EMAIL_FILE_NAME, dto.getEmail())) {
					throw new Exception(publicService.text("backend.error.register.email-spam"));
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		BaseRegisterDto baseRegisterDto = registerBase(dto);
		user = baseRegisterDto.getUser();

		user.setPassword(passwordEncoder.encode(dto.getPassword()));
		user = userRepo.saveAndFlush(user);

		UserRegisterToken token = createUserToken(user);
		return token.getContent();
	}

	public void registerOauth(RegisterDto dto) throws Exception {

		String email = authService.getOauth2Email();

		User user = userRepo.findByEmail(email);
		if (user != null) {
			throw new Exception(publicService.text("backend.error.register.email-exists"));
		}

		dto.setEmail(email);
		BaseRegisterDto baseRegisterDto = registerBase(dto);
		user = baseRegisterDto.getUser();
		user.setConfirmed(true);
		userRepo.saveAndFlush(user);
		
		userService.updateUserInfo(user);
		
		mailService.sendAccountConfirmed(user);
	}

	public UserRegisterToken createUserToken(User user) throws Exception {
		UserRegisterToken token = generateToken(user);
		user.setRegisterToken(token);
		user = userRepo.saveAndFlush(user);
		mailService.sendRegistrationMail(user, token);
		return token;
	}

	public UserRegisterToken generateToken(User user) {
		UserRegisterToken token = new UserRegisterToken();
		token.setContent(RandomStringUtils.randomAlphanumeric(tokenLength));
		token.setDate(new Date());
		token.setUser(user);
		return registerTokenRepo.saveAndFlush(token);
	}

	public User registerConfirm(String tokenString) throws Exception {
		UserRegisterToken token = registerTokenRepo.findByContent(tokenString);

		if (token == null) {
			throw new Exception();
		}

		User user = token.getUser();

		if (user == null) {
			throw new Exception();
		}

		if (user.isConfirmed()) {
			throw new Exception();
		}

		user.setConfirmed(true);
		user.setRegisterToken(null);
		user = userRepo.saveAndFlush(user);
		
		mailService.sendAccountConfirmed(user);
		
		return user;
	}

	private BaseRegisterDto registerBase(RegisterDto dto) throws Exception {
		// check minimum age
		int userAge = Tools.calcUserAge(dto.getDateOfBirth());
		if (userAge < minAge) {
			throw new Exception(publicService.text("backend.error.register.min-age"));
		}

		User user = new User();
		user.setEmail(dto.getEmail().toLowerCase());
		user.setFirstName(dto.getFirstName());
		int userMinAge = userAge - ageRange;
		int userMaxAge = userAge + ageRange;
		if (userMinAge < minAge) {
			userMinAge = minAge;
		}
		if (userMaxAge > maxAge) {
			userMaxAge = maxAge;
		}

		user.setPreferedMinAge(userMinAge);
		user.setPreferedMaxAge(userMaxAge);
		user.setGender(genderRepo.findById(dto.getGender()).orElse(null));
		user.setIntention(userIntentionRepo.findById(dto.getIntention()).orElse(null));

		UserDates dates = new UserDates();
		Date today = new Date();
		dates.setActiveDate(today);
		dates.setCreationDate(today);
		dates.setDateOfBirth(dto.getDateOfBirth());
		dates.setIntentionChangeDate(today);
		dates.setMessageCheckedDate(today);
		dates.setMessageDate(today);
		dates.setNotificationCheckedDate(today);
		dates.setNotificationDate(today);
		dates.setUser(user);
		user.setDates(dates);

		// resolves hibernate issue with null Collections with orphanremoval
		// https://hibernate.atlassian.net/browse/HHH-9940
		user.setInterests(new ArrayList<UserInterest>());
		user.setImages(new ArrayList<UserImage>());
		user.setDonations(new ArrayList<UserDonation>());
		user.setLikes(new ArrayList<UserLike>());
		user.setLikedBy(new ArrayList<UserLike>());
		user.setConversations(new ArrayList<Conversation>());
		user.setMessageReceived(new ArrayList<Message>());
		user.setMessageSent(new ArrayList<Message>());
		user.setNotifications(new ArrayList<UserNotification>());
		user.setNotificationsFrom(new ArrayList<UserNotification>());
		user.setHiddenByUsers(new ArrayList<UserHide>());
		user.setHiddenUsers(new ArrayList<UserHide>());
		user.setBlockedByUsers(new ArrayList<UserBlock>());
		user.setBlockedUsers(new ArrayList<UserBlock>());
		user.setReported(new ArrayList<UserReport>());
		user.setReportedByUsers(new ArrayList<UserReport>());
		user.setWebPush(new ArrayList<UserWebPush>());

		user.setNumberProfileViews(0);
		user.setNumberSearches(0);

		user = userRepo.saveAndFlush(user);

		userService.updateUserInfo(user);

		BaseRegisterDto baseRegisterDto = new BaseRegisterDto();
		baseRegisterDto.setRegisterDto(dto);
		baseRegisterDto.setUser(user);
		return baseRegisterDto;
	}
	
	private static boolean isValidEmailAddress(String email) {
	   try {
	      InternetAddress a = new InternetAddress(email);
	      a.validate();
	      return true;
	   } catch (AddressException ex) {
	      return false;
	   }
	}
}
