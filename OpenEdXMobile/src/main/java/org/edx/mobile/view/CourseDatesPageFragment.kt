package org.edx.mobile.view

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import org.edx.mobile.R
import org.edx.mobile.databinding.FragmentCourseDatesPageBinding
import org.edx.mobile.exception.ErrorMessage
import org.edx.mobile.http.HttpStatus
import org.edx.mobile.http.HttpStatusException
import org.edx.mobile.http.notifications.FullScreenErrorNotification
import org.edx.mobile.http.notifications.SnackbarErrorNotification
import org.edx.mobile.interfaces.OnDateBlockListener
import org.edx.mobile.model.api.EnrolledCoursesResponse
import org.edx.mobile.model.course.CourseBannerInfoModel
import org.edx.mobile.model.course.CourseComponent
import org.edx.mobile.module.analytics.Analytics
import org.edx.mobile.util.BrowserUtil
import org.edx.mobile.util.CourseDateUtil
import org.edx.mobile.util.UiUtil
import org.edx.mobile.view.adapters.CourseDatesAdapter
import org.edx.mobile.view.dialog.AlertDialogFragment
import org.edx.mobile.viewModel.CourseDateViewModel
import org.edx.mobile.viewModel.ViewModelFactory

class CourseDatesPageFragment : OfflineSupportBaseFragment() {

    private lateinit var errorNotification: FullScreenErrorNotification

    private lateinit var binding: FragmentCourseDatesPageBinding
    private lateinit var viewModel: CourseDateViewModel
    private var onDateItemClick: OnDateBlockListener = object : OnDateBlockListener {
        override fun onClick(link: String, blockId: String) {
            val component = courseManager.getComponentByIdFromAppLevelCache(courseData.courseId, blockId)
            if (blockId.isNotEmpty() && component != null) {
                environment.router.showCourseUnitDetail(this@CourseDatesPageFragment,
                        REQUEST_SHOW_COURSE_UNIT_DETAIL, courseData, null, blockId, false)
                environment.analyticsRegistry.trackDatesCourseComponentTapped(courseData.courseId, component.id, component.type.toString().toLowerCase(), link)
            } else {
                showOpenInBrowserDialog(link)
                if (blockId.isNotEmpty()) {
                    environment.analyticsRegistry.trackUnsupportedComponentTapped(courseData.courseId, blockId, link)
                }
            }
        }
    }
    private var courseData: EnrolledCoursesResponse = EnrolledCoursesResponse()
    private var isSelfPaced: Boolean = true

    companion object {
        @JvmStatic
        fun makeArguments(courseData: EnrolledCoursesResponse): Bundle {
            val courseBundle = Bundle()
            courseBundle.putSerializable(Router.EXTRA_COURSE_DATA, courseData)
            return courseBundle
        }
    }

    override fun isShowingFullScreenError(): Boolean {
        return errorNotification.isShowing
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_course_dates_page, container, false)
        return binding.root
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SHOW_COURSE_UNIT_DETAIL && resultCode == Activity.RESULT_OK
                && data != null) {
            val outlineComp: CourseComponent? = courseManager.getCourseDataFromAppLevelCache(courseData.courseId)
            outlineComp?.let {
                navigateToCourseUnit(data, courseData, outlineComp)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this, ViewModelFactory()).get(CourseDateViewModel::class.java)

        courseData = arguments?.getSerializable(Router.EXTRA_COURSE_DATA) as EnrolledCoursesResponse
        isSelfPaced = courseData.course.isSelfPaced

        errorNotification = FullScreenErrorNotification(binding.swipeContainer)

        binding.swipeContainer.setOnRefreshListener {
            // Hide the progress bar as swipe layout has its own progress indicator
            binding.loadingIndicator.loadingIndicator.visibility = View.GONE
            errorNotification.hideError()
            viewModel.fetchCourseDates(courseID = courseData.courseId, isSwipeRefresh = true)
        }
        UiUtil.setSwipeRefreshLayoutColors(binding.swipeContainer)
        initObserver()
    }

    override fun onResume() {
        super.onResume()
        viewModel.fetchCourseDates(courseID = courseData.courseId, isSwipeRefresh = false)
    }

    private fun initObserver() {
        viewModel.showLoader.observe(this, Observer { showLoader ->
            binding.loadingIndicator.loadingIndicator.visibility = if (showLoader) View.VISIBLE else View.GONE
        })

        viewModel.bannerInfo.observe(this, Observer {
            initDatesBanner(it)
        })

        viewModel.courseDates.observe(this, Observer { dates ->
            if (dates.courseDateBlocks.isNullOrEmpty()) {
                viewModel.setError(ErrorMessage.COURSE_DATES_CODE, HttpStatus.NO_CONTENT, getString(R.string.course_dates_unavailable_message))
            } else {
                dates.organiseCourseDates()
                binding.rvDates.apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = CourseDatesAdapter(dates.courseDatesMap, onDateItemClick)
                }
            }
        })

        viewModel.resetCourseDates.observe(this, Observer { resetCourseDates ->
            if (resetCourseDates != null) {
                showShiftDateSnackBar(true)
            }
        })

        viewModel.errorMessage.observe(this, Observer { errorMsg ->
            if (errorMsg != null) {
                if (errorMsg.throwable is HttpStatusException) {
                    when (errorMsg.throwable.statusCode) {
                        HttpStatus.UNAUTHORIZED -> {
                            environment.router?.forceLogout(contextOrThrow,
                                    environment.analyticsRegistry,
                                    environment.notificationDelegate)
                            return@Observer
                        }
                        else ->
                            errorNotification.showError(contextOrThrow, errorMsg.throwable, -1, null)
                    }
                } else {
                    when (errorMsg.errorCode) {
                        ErrorMessage.COURSE_DATES_CODE ->
                            errorNotification.showError(contextOrThrow, errorMsg.throwable, -1, null)
                        ErrorMessage.BANNER_INFO_CODE ->
                            initDatesBanner(null)
                        ErrorMessage.COURSE_RESET_DATES_CODE ->
                            showShiftDateSnackBar(false)
                    }
                }
            }
        })

        viewModel.swipeRefresh.observe(this, Observer { enableSwipeListener ->
            binding.swipeContainer.isRefreshing = enableSwipeListener
        })
    }

    /**
     * Initialized dates info banner on CourseDatesPageFragment
     *
     * @param courseBannerInfo object of course deadline info
     */
    private fun initDatesBanner(courseBannerInfo: CourseBannerInfoModel?) {
        if (courseBannerInfo == null || courseBannerInfo.hasEnded) {
            binding.banner.containerLayout.visibility = View.GONE
            return
        }
        CourseDateUtil.setupCourseDatesBanner(view = binding.banner.root, courseId = courseData.courseId,
                enrollmentMode = courseData.mode, isSelfPaced = isSelfPaced, screenName = Analytics.Screens.PLS_COURSE_DATES,
                analyticsRegistry = environment.analyticsRegistry, courseBannerInfoModel = courseBannerInfo,
                clickListener = View.OnClickListener { viewModel.resetCourseDatesBanner(courseID = courseData.courseId) })
    }

    private fun showShiftDateSnackBar(isSuccess: Boolean) {
        val snackbarErrorNotification = SnackbarErrorNotification(binding.root)
        snackbarErrorNotification.showError(
                if (isSuccess) R.string.assessment_shift_dates_success_msg else R.string.course_dates_reset_unsuccessful,
                null, 0, SnackbarErrorNotification.COURSE_DATE_MESSAGE_DURATION, null)
        environment.analyticsRegistry.trackPLSCourseDatesShift(courseData.courseId, courseData.mode, Analytics.Screens.PLS_COURSE_DATES, isSuccess)
    }

    private fun showOpenInBrowserDialog(link: String) {
        AlertDialogFragment.newInstance(null, getString(R.string.assessment_not_available),
                getString(R.string.assessment_view_on_web), { dialogInterface: DialogInterface, i: Int -> BrowserUtil.open(activity, link) },
                getString(R.string.label_cancel), null).show(childFragmentManager, null)
    }
}
